package com.anthrobyte.camunda.aiagent.auth.oauth2;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for the OAuth 2.0 client-credentials grant (RFC 6749 section 4.4).
 *
 * @param tokenUri token endpoint
 * @param clientId client identifier
 * @param clientSecret client secret. Never logged, and redacted in {@link #toString()}.
 * @param scope optional {@code scope} parameter
 * @param audience optional {@code audience} parameter (used by Auth0, Okta, Keycloak and others)
 * @param clientAuthentication how the client authenticates at the token endpoint
 * @param additionalParameters extra form parameters required by the identity provider
 * @param requestTimeout overall timeout for one token request
 * @param defaultTokenLifetime lifetime assumed when the response carries no {@code expires_in}
 */
public record ClientCredentialsSettings(
    URI tokenUri,
    String clientId,
    String clientSecret,
    String scope,
    String audience,
    ClientAuthentication clientAuthentication,
    Map<String, String> additionalParameters,
    Duration requestTimeout,
    Duration defaultTokenLifetime) {

  public enum ClientAuthentication {
    /** HTTP Basic authentication header (RFC 6749 section 2.3.1). The default. */
    CLIENT_SECRET_BASIC,
    /** {@code client_id} and {@code client_secret} as form parameters. */
    CLIENT_SECRET_POST
  }

  public ClientCredentialsSettings {
    Objects.requireNonNull(tokenUri, "tokenUri must not be null");
    Objects.requireNonNull(clientId, "clientId must not be null");
    Objects.requireNonNull(clientSecret, "clientSecret must not be null");
    Objects.requireNonNull(clientAuthentication, "clientAuthentication must not be null");
    Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    Objects.requireNonNull(defaultTokenLifetime, "defaultTokenLifetime must not be null");
    additionalParameters = additionalParameters == null ? Map.of() : Map.copyOf(additionalParameters);
  }

  @Override
  public String toString() {
    return "ClientCredentialsSettings{tokenUri="
        + tokenUri
        + ", clientId="
        + clientId
        + ", clientSecret=[REDACTED], scope="
        + scope
        + ", audience="
        + audience
        + ", clientAuthentication="
        + clientAuthentication
        + ", additionalParameters="
        + additionalParameters.keySet()
        + ", requestTimeout="
        + requestTimeout
        + ", defaultTokenLifetime="
        + defaultTokenLifetime
        + "}";
  }
}
