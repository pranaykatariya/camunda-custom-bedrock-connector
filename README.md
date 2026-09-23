# Organization AI Agent Connector Runtime

A Camunda 8.9 connector runtime that runs the **standard, unmodified Camunda AI Agent connector**
(Task and Sub-process) and changes one thing: **how the AWS Bedrock provider authenticates.**
Every Bedrock AI Agent served by this runtime calls the organization Bedrock gateway with
organization headers (`x-bam-token: <token>`, plus configurable `Accept` and `Host`) instead of
AWS SigV4.

* Agent orchestration, tools, MCP, memory, prompts, tool calling, the Bedrock Converse protocol and
  element semantics are Camunda's and LangChain4j's code, untouched.
* All other providers (Anthropic, Azure OpenAI, Vertex AI, OpenAI, OpenAI-compatible) behave
  exactly like the standard connector.
* Credentials are managed by the runtime and are never stored in BPMN or process variables. The
  token comes from a pluggable `AccessTokenSource`. Until the organization's token generation
  exists, a **placeholder** random, unsigned JWT is sent.
* Built on `io.camunda.connector:spring-boot-starter-camunda-connectors` and
  `io.camunda.connector:connector-agentic-ai` **8.9.12**. All other versions come from Camunda's
  own BOM.

## How it hooks in (short version)

Camunda registers `ChatModelFactory` as `@ConditionalOnMissingBean`. This project provides that
bean as a small router:

```
AI Agent (Camunda) → Langchain4JAiFrameworkAdapter (Camunda)
  → ChatModelFactory  ◄── OrganizationGatewayChatModelFactory (this project)
      ├─ Bedrock     → OrganizationBedrockChatModelBuilder
      │                  BedrockRuntimeClient (no signing) + BedrockChatModel (LangChain4j)
      │                  → Apache SdkHttpClient ◄── AuthenticatingSdkHttpClient adds org headers
      │                  → Organization Bedrock gateway → Bedrock
      └─ any other   → ChatModelFactoryImpl (Camunda), unchanged
```

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start

```bash
./mvnw verify                                   # 113 tests, no external services needed

export CAMUNDA_CLIENT_MODE=saas CAMUNDA_CLIENT_CLOUD_CLUSTERID=… CAMUNDA_CLIENT_CLOUD_REGION=…
export CAMUNDA_CLIENT_AUTH_CLIENTID=… CAMUNDA_CLIENT_AUTH_CLIENTSECRET=…
export ORG_AI_GATEWAY_HOST_HEADER=bedrock-gateway.internal.example   # placeholder until known
java -jar target/org-ai-agent-connector-runtime-1.0.0-SNAPSHOT-exec.jar
```

Then upload `element-templates/*.json` to Modeler, use **AI Agent Task / Sub-process
(Organization Bedrock Gateway)**, set the region, the gateway endpoint and the model, and deploy.
The gateway URL lives on the element ("Custom endpoint"): the runtime uses whatever is configured
there, with no allow-list.

## Project layout

```
src/main/java/com/anthrobyte/camunda/aiagent/
  OrgAiAgentConnectorRuntimeApplication.java   Spring Boot main class
  auth/        credential abstraction, no Camunda/HTTP dependencies
    OrganizationAuthenticationProvider          headers for the next request (implement for custom schemes)
    AccessTokenSource                           one new token (plug in the organization JWT here)
    CachingTokenAuthenticationProvider          token cache, expiry skew, single-flight refresh, invalidation
    PlaceholderJwtTokenSource                   random unsigned JWT (placeholder)
    StaticHeadersAuthenticationProvider
    GatewayCredentials, AccessToken             values redacted in toString
    oauth2/ClientCredentialsTokenClient         RFC 6749 client-credentials request
    OrganizationAuthentication(Unavailable)Exception, AuthenticationFailureReason
  transport/   AWS SDK HTTP client decorator, no Camunda dependencies
    AuthenticatingSdkHttpClient(+Builder)       org headers per attempt, 401 retry-once, strips SigV4 headers
  camunda/     the only Camunda integration points
    OrganizationGatewayChatModelFactory         overrides Camunda's ChatModelFactory bean (router)
    OrganizationBedrockChatModelBuilder         Bedrock client/model for the gateway
    CamundaBedrockClientParity                  everything copied from Camunda's Bedrock setup
    OrganizationAuthenticatedChatModel          fail closed before sending, sanitized auth errors
  config/
    OrganizationAuthAutoConfiguration           @AutoConfiguration(before = AgenticAiConnectorsAutoConfiguration)
    OrganizationAuthProperties                  organization.ai-gateway.auth.*
src/main/resources/application.yml              job types, disabled extra connectors, ORG_* mapping
element-templates/                              generated from Camunda's official templates (Bedrock only)
scripts/generate-element-templates.py           the generator
deploy/kubernetes/                              example manifests
Dockerfile
```

## Tests

| Suite | What it proves |
|---|---|
| `OrganizationBedrockChatModelBuilderTest` | real AWS SDK + LangChain4j against a fake gateway: `x-bam-token`/`Accept`/`Host` sent, **no** `AWS4-HMAC-SHA256`/`X-Amz-*`, element AWS keys and API key ignored, URL-encoded model id, token cached and refreshed after expiry, 401 → invalidate + exactly one retry, persistent 401/403 sanitized, token failures fail closed with **no request sent**, endpoint missing or not a usable URL rejected while any other URL is used as configured, region/endpoint/`apiCallTimeout` and default fallback, `inferenceConfig` mapping, **request body identical to Camunda's own Bedrock factory**, closing the model closes the client |
| `OrganizationGatewayChatModelFactoryTest` | every Bedrock config uses org auth (no fallback), OpenAI-compatible is no longer org-authenticated, other providers delegated untouched |
| `AuthenticatingSdkHttpClientTest` | header replacement and SigV4 header removal, everything else unchanged, Host override, every target gets the org headers, 401 retry with the same body, no retry when disabled / not refreshable, close |
| `CachingTokenAuthenticationProviderTest` | caching, expiry and skew, refresh, identity-based invalidation, 64-thread concurrent fetch/refresh = 1 token request, shared failure, bounded waiting, sanitized unexpected source errors, header order |
| `PlaceholderJwtTokenSourceTest` | JWT shape, expiry, randomness, startup warning, token never logged |
| `ClientCredentialsTokenClientTest` | OAuth2 success (Basic and POST), permanent vs transient failures, malformed responses, timeouts, no secrets echoed or logged |
| `OrganizationBedrockAiAgentIT` | real Camunda auto-configuration + `AiAgentFunction` with Bedrock: tool-calling round trip, token caching, transparent refresh on 401, sanitized `FAILED_MODEL_CALL`, IdP outage and an unusable endpoint fail closed, LLM errors unchanged |
| `StandardBehaviourRegressionIT` | disabled = Camunda's own beans; enabled = OpenAI-compatible byte-identical to the standard connector; Task and Sub-process share the overridden factory |
| `OrganizationAuthAutoConfigurationTest` | all modes, custom `AccessTokenSource` / provider beans, exactly one `ChatModelFactory`, fail-fast validation naming env vars without leaking values |
| `LoggingIT` | whole AI Agent flow at TRACE: expected log lines appear; no token, client secret or element AWS key is ever logged |
| `RuntimeApplicationSmokeIT` | the real application + `application.yml`: bean replaced, `x-bam-token` + `Accept` + `Host` defaults, custom job types |

## Documentation

* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): execution path, extension point, why it is safe, request walkthrough
* [docs/CONFIGURATION.md](docs/CONFIGURATION.md): every property and environment variable, plugging in the real token
* [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): build, Docker, Kubernetes, hybrid SaaS, **BPMN/Modeler changes**
* [docs/UPGRADING.md](docs/UPGRADING.md): upgrade procedure and each Camunda assumption with its tripwire
* [docs/SECURITY.md](docs/SECURITY.md): secret handling and threat/control/test matrix
