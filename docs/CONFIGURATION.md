# Configuration reference

All settings live under `organization.ai-gateway.auth.*` (own namespace, so no clash with future
Camunda properties). `application.yml` maps them to `ORG_*` environment variables. Every secret
**must** come from the environment, typically a Kubernetes Secret or a secret-manager agent/CSI
driver. Nothing secret belongs in `application.yml`, in the image or in BPMN.

```mermaid
flowchart LR
    cm["ConfigMap<br/>non-secret settings"]
    secret["Secret / secret manager<br/>ORG_AI_CLIENT_SECRET · …"]
    env["Environment variables<br/>ORG_* · CAMUNDA_CLIENT_*"]
    yml["application.yml<br/>placeholders only"]
    props["OrganizationAuthProperties<br/>organization.ai-gateway.auth.*"]
    validate{"validate()"}
    fail["Startup fails<br/>lists offending property names,<br/>never values"]
    beans["Beans created<br/>startup log: mode, endpoints, header names"]

    cm --> env
    secret --> env
    env --> yml --> props --> validate
    validate -- "invalid" --> fail
    validate -- "ok" --> beans

    classDef ours fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef ext fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    classDef cfg fill:#F1EDFF,stroke:#7B61FF,stroke-width:1.5px,color:#2E1F7A
    class cm,secret ext
    class env,yml,props cfg
    class validate,beans ours
    class fail bad
```

Colours: 🟦 Camunda, unchanged · 🟧 this project · 🟩 external · 🟥 fail closed · 🟪 configuration.

## Environment variables

### Camunda cluster (standard Camunda client, not specific to this project)

| Variable | SaaS (hybrid) | Self-Managed |
|---|---|---|
| `CAMUNDA_CLIENT_MODE` | `saas` | `self-managed` |
| `CAMUNDA_CLIENT_CLOUD_CLUSTERID` | cluster id | – |
| `CAMUNDA_CLIENT_CLOUD_REGION` | e.g. `bru-2` | – |
| `CAMUNDA_CLIENT_AUTH_CLIENTID` / `CAMUNDA_CLIENT_AUTH_CLIENTSECRET` | API client credentials (**secret**) | OIDC client credentials (**secret**) |
| `CAMUNDA_CLIENT_GRPCADDRESS` / `CAMUNDA_CLIENT_RESTADDRESS` | – | Zeebe gateway addresses |

Do **not** set `CAMUNDA_CONNECTOR_RUNTIME_SAAS` on this runtime. Camunda then rejects the
`defaultCredentialsChain` authentication that the organization templates use.

### Job types

| Variable | Default (`application.yml`) | Element |
|---|---|---|
| `ORG_AI_AGENT_TASK_TYPE` → `CONNECTOR_AI_AGENT_TYPE` | `org.ai-gateway:aiagent:1` | AI Agent **Task** |
| `ORG_AI_AGENT_SUBPROCESS_TYPE` → `CONNECTOR_AI_AGENT_JOB_WORKER_TYPE` | `org.ai-gateway:aiagent-job-worker:1` | AI Agent **Sub-process** |

### Organization Bedrock authentication

| Property | Env variable | Default | Description |
|---|---|---|---|
| `enabled` | `ORG_AI_GATEWAY_AUTH_ENABLED` | `true` (yml) / `false` (code) | Master switch. `false` = exactly the standard connector. With `true`, startup fails with a message naming `ORG_AI_GATEWAY_URL` if it is missing. |
| `allowed-endpoints` | `ORG_AI_GATEWAY_URL` | – (**required**) | Comma-separated Bedrock gateway base URLs. Every Bedrock AI Agent must use one as its custom endpoint (same scheme, host and port; path on a segment boundary). Anything else fails the job. |
| `mode` | `ORG_AI_GATEWAY_AUTH_MODE` | `PLACEHOLDER_JWT` | `PLACEHOLDER_JWT`, `OAUTH2_CLIENT_CREDENTIALS`, `STATIC_HEADERS` or `CUSTOM`. |
| `retry-on-unauthorized` | – | `true` | After a gateway 401, refresh the token and resend once. The token is invalidated either way. |
| `allow-insecure-http` | – | `false` | Permit `http://` gateway/token URLs. **Local development only.** |
| `static-headers[n].name` / `.value` | – | `Accept: application/json`, `Host: ${ORG_AI_GATEWAY_HOST_HEADER}` | Headers sent on every request next to the token. In `STATIC_HEADERS` mode they are the credentials. `Host` must be `host[:port]`, not a URL. |
| `static-headers[1].value` (Host) | `ORG_AI_GATEWAY_HOST_HEADER` | `bedrock-gateway.placeholder.invalid` (**placeholder**) | The `Host` header value the gateway expects. |

#### Which credentials each `mode` produces

```mermaid
flowchart TD
    ownProvider{"Own OrganizationAuthenticationProvider<br/>bean declared?"}
    useOwn["Your provider is used<br/>the built-in one backs off"]
    mode{"mode"}
    placeholder["PlaceholderJwtTokenSource<br/>random unsigned JWT, alg none<br/>not for production"]
    oauth["ClientCredentialsTokenClient<br/>POST token-uri"]
    customBean{"AccessTokenSource<br/>bean declared?"}
    yours["Your AccessTokenSource"]
    noBean["Startup fails<br/>mode=CUSTOM requires a bean"]
    staticH["StaticHeadersAuthenticationProvider<br/>static headers are the credentials<br/>no token, no refresh"]
    cache["CachingTokenAuthenticationProvider<br/>cache · single-flight refresh · invalidate on 401<br/>token.* settings apply"]
    out(["GatewayCredentials<br/>x-bam-token + Accept + Host"])

    ownProvider -- "yes" --> useOwn --> out
    ownProvider -- "no" --> mode
    mode -- "PLACEHOLDER_JWT (default)" --> placeholder --> cache
    mode -- "OAUTH2_CLIENT_CREDENTIALS" --> oauth --> cache
    mode -- "CUSTOM" --> customBean
    customBean -- "yes" --> yours --> cache
    customBean -- "no" --> noBean
    mode -- "STATIC_HEADERS" --> staticH --> out
    cache --> out

    classDef ours fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    classDef cfg fill:#F1EDFF,stroke:#7B61FF,stroke-width:1.5px,color:#2E1F7A
    classDef you fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    class placeholder,oauth,staticH,cache,out ours
    class ownProvider,mode,customBean cfg
    class useOwn,yours you
    class noBean bad
```

#### `token.*` (all modes except `STATIC_HEADERS`)

| Property | Env variable | Default | Description |
|---|---|---|---|
| `header-name` | `ORG_AI_GATEWAY_TOKEN_HEADER` | `x-bam-token` | Header that carries the token. |
| `header-value-template` | `ORG_AI_GATEWAY_TOKEN_HEADER_TEMPLATE` | `{token}` | Value; `{token}` is replaced (e.g. `Bearer {token}`). |
| `refresh-skew` | – | `PT60S` | Treat the token as expired this long before it expires. Capped at half the lifetime. |
| `refresh-wait-timeout` | – | `PT15S` | Max wait for an in-flight refresh before failing with a transient error. |

The life of a cached token (one cache per pod):

```mermaid
stateDiagram-v2
    direction LR
    [*] --> NoToken
    NoToken --> Refreshing: getCredentials()
    Refreshing --> Valid: token fetched
    Refreshing --> NoToken: fetch failed, waiters share the error
    Valid --> Valid: getCredentials(), lock-free read
    Valid --> NoToken: refresh-skew before expiresAt
    Valid --> NoToken: gateway 401 invalidates this token

    note right of Refreshing
        One thread calls the token source.
        Others wait up to refresh-wait-timeout,
        then reuse its result.
    end note

    classDef ok fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    classDef busy fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef empty fill:#F1EDFF,stroke:#7B61FF,stroke-width:1.5px,color:#2E1F7A
    class Valid ok
    class Refreshing busy
    class NoToken empty
```

The skew is capped at half the token's lifetime, so a short-lived token is never "expired on arrival".

#### `placeholder-jwt.*` (mode `PLACEHOLDER_JWT`)

| Property | Default | Description |
|---|---|---|
| `lifetime` | `PT5M` | `exp` of each generated JWT (`alg: none`, random `jti`). |

#### `oauth2.*` (mode `OAUTH2_CLIENT_CREDENTIALS`)

| Property | Env variable | Default | Description |
|---|---|---|---|
| `token-uri` | `ORG_AI_TOKEN_URL` | – (**required**) | OAuth 2.0 token endpoint. |
| `client-id` | `ORG_AI_CLIENT_ID` | – (**required**) | Client id. |
| `client-secret` | `ORG_AI_CLIENT_SECRET` | – (**required, secret**) | Client secret. |
| `scope` / `audience` | `ORG_AI_TOKEN_SCOPE` / `ORG_AI_TOKEN_AUDIENCE` | – | Optional. |
| `client-authentication` | `ORG_AI_TOKEN_CLIENT_AUTH` | `CLIENT_SECRET_BASIC` | Or `CLIENT_SECRET_POST`. |
| `additional-parameters.*` | – | – | Extra form parameters. |
| `connect-timeout` / `request-timeout` | – | `PT5S` / `PT10S` | Token endpoint timeouts. |
| `default-token-lifetime` | – | `PT5M` | Used when the response has no `expires_in`. |

## What goes on the wire

```
POST https://<gateway>/<base>/model/<model id, URL-encoded>/converse
x-bam-token: <token>
Accept: application/json
Host: <ORG_AI_GATEWAY_HOST_HEADER>
Content-Type: application/json
(no Authorization, X-Amz-Date, X-Amz-Security-Token or X-Amz-Content-Sha256)
```

How `AuthenticatingSdkHttpClient` turns the AWS SDK's request into that, on every attempt:

```mermaid
flowchart LR
    sdk["Request from the AWS SDK<br/>POST …/model/MODEL_ID/converse<br/>Content-Type · body"]
    allow{"URI on the<br/>allow-list?"}
    refuse["Refused<br/>ENDPOINT_NOT_PERMITTED<br/>nothing sent"]
    strip["Remove<br/>Authorization · X-Amz-Date<br/>X-Amz-Security-Token · X-Amz-Content-Sha256<br/>and any header named like an org header"]
    add["Add<br/>x-bam-token · Accept · Host"]
    wire(["Sent unsigned<br/>to the gateway"])

    sdk --> allow
    allow -- "no" --> refuse
    allow -- "yes" --> strip --> add --> wire

    classDef cam fill:#E8F0FE,stroke:#4A7BD0,stroke-width:1.5px,color:#1A3A6B
    classDef ours fill:#FFF1E0,stroke:#F08A24,stroke-width:1.5px,color:#6B3A00
    classDef ext fill:#E6F6EC,stroke:#2E9E5B,stroke-width:1.5px,color:#0F4D2A
    classDef bad fill:#FDECEC,stroke:#D64545,stroke-width:1.5px,color:#7A1414
    class sdk cam
    class allow,strip,add ours
    class wire ext
    class refuse bad
```

## Plugging in the organization JWT

The placeholder is meant to be replaced. Add a bean and set `ORG_AI_GATEWAY_AUTH_MODE=CUSTOM`:

```java
@Bean
AccessTokenSource organizationJwt(/* your dependencies */) {
  return () -> {
    String jwt = /* generate or fetch the JWT */;
    Instant expiresAt = /* its expiry */;
    return new AccessToken(jwt, expiresAt);
  };
}
```

The runtime caches the token until `refresh-skew` before `expiresAt`, refreshes it single-flight,
invalidates it after a gateway 401, and sends it as `x-bam-token`. Throw
`OrganizationAuthenticationException` for permanent failures and
`OrganizationAuthenticationUnavailableException` for transient ones. Any other exception becomes a
sanitized `TOKEN_SOURCE_FAILED`, and its message is never logged.

When many AI Agent jobs need a token at the same moment, your source is called **once**:

```mermaid
sequenceDiagram
    participant J1 as Job 1
    participant J2 as Job 2
    participant J3 as Job 3
    participant C as CachingToken<br/>AuthenticationProvider
    participant S as Your<br/>AccessTokenSource

    J1->>C: getCredentials()
    Note right of C: cache empty or expired,<br/>Job 1 takes the refresh lock
    C->>S: requestToken()
    J2->>C: getCredentials()
    J3->>C: getCredentials()
    Note over J2,J3: wait for the lock<br/>(at most refresh-wait-timeout)
    S-->>C: AccessToken(jwt, expiresAt)
    C-->>J1: credentials
    C-->>J2: same credentials (double-check, no new request)
    C-->>J3: same credentials
    Note over C,S: If requestToken() had failed, Job 2 and Job 3<br/>would get the same sanitized error instead of retrying
```

For a mechanism that is not a single token (for example per-request signing), register an
`OrganizationAuthenticationProvider` bean instead. The built-in one backs off.

## Proxy

The gateway client and the token endpoint client honour Camunda's proxy variables
(`CONNECTOR_HTTP_PROXY_*`, `CONNECTOR_HTTPS_PROXY_*`, `CONNECTOR_HTTP_NON_PROXY_HOSTS`), plus the
JVM `http(s).proxyHost` system properties for the gateway client (`useSystemPropertyValues(true)`,
as in Camunda's Bedrock client).

## TLS

Both clients use the JVM default truststore. If the gateway or the token endpoint uses an internal
CA, add it to the image's truststore (or set `-Djavax.net.ssl.trustStore=…`).

## Logging

Log lines from this project carry SLF4J key/value pairs (`gatewayHost`, `httpStatus`, `attempt`,
`tokenEndpoint`, `expiresAt`, …). In the default plain-text output they are appended to the
message (`… call succeeded gatewayHost="gw" httpStatus="200" durationMs="812"`);
`SPRING_PROFILES_ACTIVE=json-logs` gives ECS JSON with those pairs as fields.

| Level | What is logged |
|---|---|
| `INFO` | Startup: runtime/connectors version, AI Agent job types, mode, allowed endpoints, header *names*, the bean override. Per agent turn: model creation (host, path, region, model, timeout), every gateway HTTP exchange (method, path, status, duration, attempt, AWS request id), `Bedrock chat call completed` (model, finish reason, tool calls requested, input/output/total tokens, duration). Non-Bedrock providers routed to Camunda's factory. Token refreshed (expiry, lifetime, refresh count), missing `expires_in`, slow concurrent refresh waits. 401 retry and its outcome, invalidation, SDK aborts. |
| `WARN` | Placeholder token in use, Host header still the placeholder, org auth disabled, standard AI Agent job types in use, token request/refresh failures, gateway 5xx/429, network errors, final 401/403, `Bedrock chat call failed` (error type, reason, status, duration), rejected Bedrock endpoints (with the allow list), AWS keys on the element ignored, `allow-insecure-http` |
| `ERROR` | Credentials refused for a non-allow-listed URL at request time (should never happen) |
| `DEBUG` | `Calling organization Bedrock gateway` (host, path, header names, attempt), replaced header names, why a URL did not match the allow list (`hint`: host/scheme/port/path), applied model parameters, proxy selection, gateway 401/403 responses |
| `TRACE` | Cached-token hits |

At every level, the logs never contain tokens, client secrets, header values, element AWS keys,
or request/response bodies. `LoggingIT` asserts this at `TRACE`. Never enable DEBUG on
`org.apache.http.headers` or `org.apache.http.wire` in production: they log header values.
