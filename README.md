# Organization AI Agent Connector Extension

An extension jar for the Camunda 8.9 connector runtime. The runtime keeps running the **standard,
unmodified Camunda AI Agent connector** (Task and Sub-process); this jar changes one thing:
**how the AWS Bedrock provider authenticates.** Every Bedrock AI Agent call goes to the
organization Bedrock gateway with organization headers (`x-bam-token: <token>`, plus configurable
`Accept` and `Host`) instead of AWS SigV4.

* Agent orchestration, tools, MCP, memory, prompts, tool calling, the Bedrock Converse protocol and
  element semantics are Camunda's and LangChain4j's code, untouched.
* All other providers (Anthropic, Azure OpenAI, Vertex AI, OpenAI, OpenAI-compatible) behave
  exactly like the standard connector.
* The token is a **BAM token** fetched from the BAM token endpoint with HTTP Basic auth, and
  cached. The endpoint URL, username and password are secrets supplied through the environment;
  nothing is stored in BPMN or process variables.
* Built against `io.camunda.connector:connector-agentic-ai` **8.9.12** (scope `provided`). All
  other versions come from Camunda's own BOM.

## How it hooks in

Camunda registers `ChatModelFactory` as `@ConditionalOnMissingBean`.
`OrganizationAuthAutoConfiguration` runs before Camunda's auto-configuration and provides that bean
as a small router:

```
AI Agent (Camunda) → Langchain4JAiFrameworkAdapter (Camunda)
  → ChatModelFactory  ◄── OrganizationGatewayChatModelFactory (this project)
      ├─ Bedrock     → OrganizationBedrockChatModelBuilder
      │                  BedrockRuntimeClient (no signing) + BedrockChatModel (LangChain4j)
      │                  → Apache SdkHttpClient ◄── AuthenticatingSdkHttpClient adds org headers
      │                  → Organization Bedrock gateway → Bedrock
      └─ any other   → ChatModelFactoryImpl (Camunda), unchanged
```

With `organization.ai-gateway.auth.enabled=false` nothing from this jar is active and the runtime
is exactly the standard connector.

## Build and deploy

```bash
./mvnw verify    # all unit + integration tests, no external services needed
./mvnw package   # target/org-ai-agent-connector-runtime-1.0.0-SNAPSHOT.jar (thin: only this project's classes)
```

Put the jar on the Camunda connector runtime's classpath (exactly one copy of it) and provide the
three `ORG_AI_BAM_*` secrets. Startup validates the configuration and fails fast, listing the
offending *property names* and environment variables (never their values).

## Configuration

`application.yml` (packaged in the jar) maps the settings under `organization.ai-gateway.auth.*` to
environment variables. Secrets must come from the environment, never from `application.yml` or BPMN.

| Variable | Default | Description |
|---|---|---|
| `ORG_AI_BAM_TOKEN_URL` (**secret**) | – (required) | BAM token endpoint, `https://…/api/token`. |
| `ORG_AI_BAM_USERNAME` (**secret**) | – (required) | Basic auth user name (must not contain `:`). |
| `ORG_AI_BAM_PASSWORD` (**secret**) | – (required) | Basic auth password. |
| `ORG_AI_GATEWAY_AUTH_ENABLED` | `true` (yml) / `false` (code) | Master switch. `false` = exactly the standard connector. |
| `ORG_AI_GATEWAY_HOST_HEADER` | `bedrock-gateway.placeholder.invalid` (**placeholder**, warned at startup) | `Host` header the gateway routes on: `host[:port]`, not a URL. |
| `ORG_AI_GATEWAY_TOKEN_HEADER` | `x-bam-token` | Header that carries the token. |
| `ORG_AI_GATEWAY_TOKEN_HEADER_TEMPLATE` | `{token}` | Header value; `{token}` is replaced (e.g. `Bearer {token}`). |
| `ORG_AI_AGENT_TASK_TYPE` → `CONNECTOR_AI_AGENT_TYPE` | `org.ai-gateway:aiagent:1` | Job type of the AI Agent **Task**. |
| `ORG_AI_AGENT_SUBPROCESS_TYPE` → `CONNECTOR_AI_AGENT_JOB_WORKER_TYPE` | `org.ai-gateway:aiagent-job-worker:1` | Job type of the AI Agent **Sub-process**. |

Further properties (no env mapping): `retry-on-unauthorized` (`true`: after a gateway 401, fetch a
fresh token and resend once), `token.refresh-skew` (`PT60S`, capped at half the token lifetime),
`token.refresh-wait-timeout` (`PT15S`), `bam.connect-timeout` / `bam.request-timeout` (`PT5S` /
`PT10S`), `bam.default-token-lifetime` (`PT10M`, only for a token without `iat`/`exp`),
`allow-insecure-http` (`false`: the BAM URL must be https; local development only), and
`static-headers[n]` (default `Accept: application/json` and the `Host` above). `application.yml` also switches off the other agentic connectors (MCP remote
client, A2A, ad-hoc tools schema) so this runtime never competes for the standard
`io.camunda.agenticai:*` job types.

**The gateway URL is not configured here.** Each Bedrock AI Agent element carries it as its
"Custom endpoint" and the runtime uses it exactly as configured: no allow-list, no scheme check.
Only a missing endpoint, or one that is not an absolute URL with a host, fails the job
(`ENDPOINT_NOT_CONFIGURED`).

### The BAM token and its caching

`BamTokenClient` calls `GET <ORG_AI_BAM_TOKEN_URL>` with `Authorization: Basic base64(user:password)`
and reads `bamToken` from the JSON response. The token is valid for 10 minutes;
`BamTokenCache` manages it:

* **Lazy, then cached per pod.** The first Bedrock call fetches a token; every call after that reuses
  it (a lock-free read), so BAM is called about once per 9 minutes per pod, not once per request.
* **Refreshed before it expires.** A token counts as expired `refresh-skew` (60 s) early, so it is
  replaced after ~9 minutes and a request never leaves with a token about to expire in flight.
* **Expiry from the token itself.** The lifetime is `exp - iat` from the JWT claims, applied to the
  pod's clock, so clock skew between the pod and BAM does not matter.
* **Single-flight.** When the token expires under load, one request fetches the new token and the
  others wait for it (at most `refresh-wait-timeout`), so there is no burst of BAM calls. If that
  fetch fails, the waiting requests get the same failure instead of each retrying.
* **401 from the gateway.** The rejected token is dropped, a fresh one is fetched and the request is
  resent once; a second 401 fails the job.
* **BAM failures fail closed.** Nothing is sent to the gateway without a token. A 401/403 from BAM
  (wrong credentials) is permanent; timeouts, connection errors, 408/429/5xx are transient, so
  Camunda's normal job retries try again later.

### What goes on the wire

```
POST https://<gateway>/<base>/model/<model id, URL-encoded>/converse
x-bam-token: <token>
Accept: application/json
Host: <ORG_AI_GATEWAY_HOST_HEADER>
Content-Type: application/json
(no Authorization, X-Amz-Date, X-Amz-Security-Token or X-Amz-Content-Sha256)
```

The gateway and BAM clients honour Camunda's proxy variables (`CONNECTOR_HTTP(S)_PROXY_*`,
`CONNECTOR_HTTP_NON_PROXY_HOSTS`) and use the JVM default truststore. The BAM client never follows
redirects, so the credentials only ever reach the configured host.

## BPMN / Modeler

The only change a BPMN element needs is its **task definition type**.

* **Organization templates (recommended):** `element-templates/*.json` are generated from Camunda's
  official 8.9.12 templates by `scripts/generate-element-templates.py`. They set the custom job
  type, fix the provider to Bedrock with `defaultCredentialsChain` (no AWS key fields) and make the
  custom endpoint required. Regenerate after changing job types or upgrading Camunda:
  `python3 scripts/generate-element-templates.py [--gateway-url https://…]`. In the element set the
  region, the gateway endpoint and the model.
* **Camunda's Hybrid AI Agent templates:** enter the custom task definition type, choose *AWS
  Bedrock* with *Default Credentials Chain*, and set the custom endpoint to the gateway URL.

Never put AWS keys, API keys, client secrets or tokens in BPMN. Do not set
`CAMUNDA_CONNECTOR_RUNTIME_SAAS` on this runtime, or Camunda rejects `defaultCredentialsChain`.

## Security and logging

* Every Bedrock config goes to the organization path; element AWS keys/API keys are ignored and the
  client resolves only the no-auth scheme, so no SigV4 or bearer signer runs. AWS signing headers
  are stripped.
* Credentials are fetched before the model is called and on every attempt. If they cannot be
  obtained, the job fails and nothing is sent. A final gateway 401/403 fails the job with a sanitized
  `FAILED_MODEL_CALL` error. Exceptions carry fixed messages and no cause.
* **The endpoint is not a control:** credentials go to whatever URL an element points at. Restrict
  who can deploy processes with the organization templates and consider egress policies.
* Logs contain header *names*, hosts, paths, status codes and durations, never tokens, header
  values, element AWS keys or bodies (`LoggingIT` asserts this at TRACE). Key/value pairs are
  appended to plain-text lines by the `logging.pattern.console` set in `application.yml` (Spring
  Boot's own pattern plus `%kvp`; its default ends in `%m` and would drop them). This jar ships no
  `logback-spring.xml`, so the runtime's own logging configuration applies unchanged.
  Never enable DEBUG on `org.apache.http.headers`/`org.apache.http.wire`.

## Project layout

```
src/main/java/com/anthrobyte/camunda/aiagent/
  auth/        BAM token: fetch, cache, headers; no Camunda/AWS dependencies
    BamTokenClient, BamTokenSettings            GET the BAM token (Basic auth), expiry from iat/exp
    BamTokenCache                               token cache, expiry skew, single-flight refresh, invalidation;
                                                hands out the headers for each gateway request
    GatewayCredentials, BamToken                values redacted in toString
    OrganizationAuthentication(Unavailable)Exception, AuthenticationFailureReason
  transport/   AuthenticatingSdkHttpClient(+Builder): org headers per attempt, 401 retry-once, strips SigV4 headers
  camunda/     the only Camunda integration points
    OrganizationGatewayChatModelFactory         overrides Camunda's ChatModelFactory bean (router)
    OrganizationBedrockChatModelBuilder         Bedrock client/model for the gateway
    CamundaBedrockClientParity                  everything copied from Camunda's Bedrock setup
    OrganizationAuthenticatedChatModel          fail closed before sending, sanitized auth errors
  config/      OrganizationAuthAutoConfiguration, OrganizationAuthProperties
src/main/resources/application.yml              job types, disabled extra connectors, ORG_* mapping
element-templates/, scripts/                    Modeler templates and their generator
```

## Tests

| Suite | What it proves |
|---|---|
| `OrganizationBedrockChatModelBuilderTest` | real AWS SDK + LangChain4j against a fake gateway: org headers sent, **no** SigV4/`X-Amz-*`, element AWS keys ignored, token caching and refresh, 401 → invalidate + one retry, 401/403 sanitized, token failures send nothing, unusable endpoints rejected, timeouts, **request body identical to Camunda's own Bedrock factory** |
| `OrganizationGatewayChatModelFactoryTest` | every Bedrock config uses org auth, other providers delegated untouched |
| `AuthenticatingSdkHttpClientTest` | header replacement, SigV4 header removal, Host override, 401 retry with the same body |
| `BamTokenCacheTest` | caching, skew, identity-based invalidation, 64-thread single-flight refresh, shared failure, bounded waiting |
| `OrganizationBedrockAiAgentIT` | real Camunda auto-configuration + `AiAgentFunction`: tool-calling round trip, refresh on 401, sanitized `FAILED_MODEL_CALL`, BAM outage and unusable endpoint fail closed, LLM errors unchanged |
| `StandardBehaviourRegressionIT` | disabled = Camunda's own beans; enabled = OpenAI-compatible byte-identical to the standard connector |
| `BamTokenClientTest` | Basic auth GET, expiry from `exp - iat` (clock-skew safe) with fallbacks, 401 permanent vs 408/429/5xx/timeout/unreachable transient, malformed responses, redirects not followed, credentials and tokens never logged |
| `OrganizationAuthAutoConfigurationTest` | BAM token fetched lazily, cached and sent in `x-bam-token`, missing secrets fail startup naming the env vars, https-only BAM URL, legacy `mode` setting ignored, exactly one `ChatModelFactory`, fail-fast validation without leaking values |
| `LoggingIT` | whole AI Agent flow at TRACE: expected log lines appear, no BAM password, token or element AWS key ever logged |
| `RuntimeApplicationSmokeIT` | a Spring Boot app with this jar and its `application.yml`, secrets via `ORG_AI_BAM_*`: bean replaced, BAM token + header defaults, custom job types |

Upgrading Camunda: [docs/UPGRADING.md](docs/UPGRADING.md).
