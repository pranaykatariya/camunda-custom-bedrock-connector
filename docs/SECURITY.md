# Security review

## Secrets: where they live, and where they never live

| Secret | Source | Never in |
|---|---|---|
| Organization token (`x-bam-token`) | memory only (`CachingTokenAuthenticationProvider`, one per pod), from the `AccessTokenSource` | disk, process variables, agent context, logs, exception messages |
| OAuth2 client secret / static header values | env var → Spring property (`${ORG_AI_*}`), from a Kubernetes Secret or secret manager | `application.yml` values, image, BPMN, process variables, logs, exception messages, `toString()` |
| Camunda API client secret | env var (`CAMUNDA_CLIENT_AUTH_CLIENTSECRET`) | as above |

```mermaid
flowchart TB
    store["Kubernetes Secret /<br/>secret manager"]

    subgraph pod["Runtime pod · memory only"]
        direction TB
        props["Spring properties<br/>toString() redacted"]
        source["AccessTokenSource<br/>placeholder · OAuth2 · yours"]
        cache["Token cache<br/>CachingTokenAuthenticationProvider"]
        http["AuthenticatingSdkHttpClient<br/>header added per HTTP attempt"]
    end

    gw["The endpoint configured on the element<br/>(intended: the organization Bedrock gateway)"]

    subgraph never["Never reaches"]
        direction LR
        bpmn["BPMN / element templates"]
        vars["Process variables · AgentContext<br/>conversation memory"]
        logs["Logs, at any level"]
        incidents["Exception messages / incidents"]
        disk["Disk · container image"]
    end

    store -- "env vars" --> props --> source --> cache --> http --> gw
    cache -.-x never

    classDef ours fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef ext fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    classDef cfg fill:#F1EDFF,stroke:#7B61FF,stroke-width:1.5px,color:#2E1F7A
    class props cfg
    class source,cache,http ours
    class store,gw ext
    class bpmn,vars,logs,incidents,disk bad
    style pod fill:transparent,stroke:#F08A24,stroke-width:2px,stroke-dasharray:6 4
    style never fill:transparent,stroke:#D64545,stroke-width:2px
```

The token is added **below** LangChain4j and the AWS SDK's signing step, as a header on the
outgoing HTTP attempt. It never enters `AgentContext`, conversation memory or job variables.

**Placeholder token.** Until an `AccessTokenSource` bean is provided (`mode=CUSTOM`), the default
mode sends a random, unsigned JWT (`alg: none`). A warning is logged at startup. No gateway should
accept it: treat `PLACEHOLDER_JWT` as not production-ready.

## Controls

Every Bedrock call passes these checkpoints in order. Any "no" fails the job with a sanitized
exception. A failure at checkpoint 1 or 2 happens before anything is sent.

**The endpoint itself is not a control.** There is no allow-list: the credentials are sent to the
URL configured on the element (see the threat table below).

```mermaid
flowchart TD
    start(["Bedrock AI Agent job"])
    c1{"1 · Element endpoint set and<br/>a usable absolute URL?<br/>(model creation)"}
    c2{"2 · Credentials obtained?<br/>(before the model call)"}
    c4["3 · Signing headers stripped<br/>noAuth-only scheme: no SigV4, no bearer"]
    c5{"4 · Gateway accepts<br/>the credentials?"}
    retry["Invalidate token,<br/>fetch a fresh one, resend once"]
    ok(["Response returned to<br/>LangChain4j and Camunda"])
    fail["Job fails · sanitized exception<br/>no payload, no secret, no cause<br/>→ Camunda incident"]

    start --> c1
    c1 -- "no" --> fail
    c1 -- "yes" --> c2
    c2 -- "no" --> fail
    c2 -- "yes" --> c4 --> c5
    c5 -- "yes, or any non-auth status" --> ok
    c5 -- "401 (first time)" --> retry --> c4
    c5 -- "second 401, or 403" --> fail

    classDef ours fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef ext fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    class c1,c2,c4,c5,retry ours
    class start,ok ext
    class fail bad
```

Failure reasons (`AuthenticationFailureReason`) and whether a later attempt can help:

```mermaid
flowchart LR
    subgraph permanent["Permanent · OrganizationAuthenticationException"]
        direction TB
        p1["TOKEN_REQUEST_REJECTED"]
        p2["MALFORMED_TOKEN_RESPONSE"]
        p3["TOKEN_SOURCE_FAILED"]
        p4["GATEWAY_REJECTED_CREDENTIALS · 401"]
        p5["GATEWAY_ACCESS_DENIED · 403"]
        p6["ENDPOINT_NOT_CONFIGURED"]
    end
    subgraph transient["Transient · OrganizationAuthenticationUnavailableException"]
        direction TB
        t1["TOKEN_ENDPOINT_TIMEOUT"]
        t2["TOKEN_ENDPOINT_UNREACHABLE"]
        t3["TOKEN_ENDPOINT_ERROR · 408 / 429 / 5xx"]
        t4["INTERRUPTED"]
    end

    permanent --> fix["Fix configuration,<br/>credentials or the element"]
    transient --> later["A later attempt<br/>may succeed"]

    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    classDef warn fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef cfg fill:#F1EDFF,stroke:#7B61FF,stroke-width:1.5px,color:#2E1F7A
    class p1,p2,p3,p4,p5,p6 bad
    class t1,t2,t3,t4 warn
    class fix,later cfg
    style permanent fill:transparent,stroke:#D64545,stroke-width:2px
    style transient fill:transparent,stroke:#F08A24,stroke-width:2px
```

| Threat | Control | Test |
|---|---|---|
| Modeler points the Bedrock endpoint at an attacker host to harvest organization tokens | **Not mitigated in the runtime, by design.** Any endpoint configured on the element receives the organization credentials; only a missing or unusable URL is refused. The controls are outside this runtime: who may model, review and deploy processes with the organization AI Agent templates, and egress restrictions on the pod's network. | `OrganizationBedrockChatModelBuilderTest#onlyMissingOrUnusableEndpoints…`, `…#anyConfiguredEndpointIsUsedAsTheGateway` |
| Falling back to AWS or to Camunda's built-in Bedrock auth | Every Bedrock config goes to the organization path; element AWS keys/API key are ignored; the client resolves only the no-auth scheme | `OrganizationGatewayChatModelFactoryTest#everyBedrockConfiguration…`, `…#bedrockOnAnyOtherHostStillGetsOrganizationAuthentication` |
| Unauthenticated call when the token cannot be obtained | Credentials are fetched before the model is called and on every attempt; failures throw, nothing is sent | `…#rejectedTokenRequestFailsClosed…`, `…#unavailableTokenEndpoint…`, `…#unexpectedTokenSourceError…`, `OrganizationBedrockAiAgentIT#identityProviderOutage…` |
| SigV4 or env bearer token leaking to the gateway | `authSchemeProvider` resolves only `smithy.api#noAuth`; `Authorization`/`X-Amz-*` signing headers stripped unless they are organization headers | `…#sendsOrganizationHeadersAndNoAwsSignature`, `AuthenticatingSdkHttpClientTest#…DropsAwsSigningHeaders` |
| Credentials sent in clear text | `https` required for the OAuth2 token endpoint (`allow-insecure-http` exists for local dev only). The gateway endpoint's scheme is **not** checked: a `http://` endpoint on an element sends the token in the clear. | `OrganizationAuthAutoConfigurationTest#invalidConfiguration…` |
| Credentials follow a redirect | Token client uses `Redirect.NEVER`; the AWS SDK Apache client does not follow redirects | code review |
| Secrets in logs | Only names, hosts, paths, status codes and OAuth error *codes* are logged | `LoggingIT` (TRACE), `PlaceholderJwtTokenSourceTest`, `AuthenticatingSdkHttpClientTest` |
| Secrets in exception messages (these become Camunda incidents) | Fixed message templates; remote detail only if it is a short lowercase code; no cause attached; unexpected token-source exceptions replaced by `TOKEN_SOURCE_FAILED` | `GatewayCredentialsTest`, `CachingTokenAuthenticationProviderTest#unexpectedSourceException…` |
| Secrets via `toString()` / actuator `configprops` | Redacting `toString()` on every type that holds a secret; Spring Boot hides values by default | `…#propertiesToStringRedactsAllSecrets` |
| Token-refresh stampede / IdP lockout | Single-flight refresh, shared failure for waiters, bounded wait; a fetch failure is thrown before LangChain4j's retry loop | `CachingTokenAuthenticationProviderTest` |

## Operational guidance

* Restrict who can deploy processes using the organization AI Agent templates, and review the
  "Custom endpoint" of each one: it is the only thing deciding where the organization credentials
  go. Consider egress network policies on the runtime pods as a second line.
* Replace `PLACEHOLDER_JWT` with the organization token source before production.
* Set `ORG_AI_GATEWAY_HOST_HEADER` to the real value; the default is a placeholder.
* Do not set DEBUG on `org.apache.http.headers`/`org.apache.http.wire` in production.
* Do not expose actuator endpoints beyond `health` publicly.
* Rotate `ORG_AI_CLIENT_SECRET` (OAuth2 mode) by updating the Secret and restarting the pods.
  Tokens are in memory only.
* Container: non-root uid 1001, read-only root filesystem, all capabilities dropped (see
  `deploy/kubernetes`).
