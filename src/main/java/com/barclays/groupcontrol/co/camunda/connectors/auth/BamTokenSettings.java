package com.barclays.groupcontrol.co.camunda.connectors.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings for the BAM token endpoint.
 *
 * @param tokenUrl the BAM token endpoint ({@code GET}, HTTP Basic authentication)
 * @param username Basic authentication user name
 * @param password Basic authentication password. Never logged, and redacted in {@link #toString()}.
 * @param requestTimeout overall timeout for one token request
 * @param defaultTokenLifetime lifetime assumed when the token carries no usable {@code exp} claim
 */
public record BamTokenSettings(
    URI tokenUrl,
    String username,
    String password,
    Duration requestTimeout,
    Duration defaultTokenLifetime) {

  public BamTokenSettings {
    Objects.requireNonNull(tokenUrl, "tokenUrl must not be null");
    Objects.requireNonNull(username, "username must not be null");
    Objects.requireNonNull(password, "password must not be null");
    Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    Objects.requireNonNull(defaultTokenLifetime, "defaultTokenLifetime must not be null");
  }

  @Override
  public String toString() {
    return "BamTokenSettings{tokenUrl="
        + tokenUrl
        + ", username=[REDACTED], password=[REDACTED], requestTimeout="
        + requestTimeout
        + ", defaultTokenLifetime="
        + defaultTokenLifetime
        + "}";
  }
}
