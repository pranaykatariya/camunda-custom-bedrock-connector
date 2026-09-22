package com.anthrobyte.camunda.aiagent.auth;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends fixed, non-expiring headers, for example {@code X-Client-ID} and {@code X-Client-Secret},
 * or a long-lived {@code X-Organization-Token}.
 *
 * <p>The values come from configuration, which should resolve them from environment variables or
 * Kubernetes Secrets. The credentials never change, so retrying after a 401 is pointless and
 * {@link #supportsRefresh()} is {@code false}.
 */
public final class StaticHeadersAuthenticationProvider implements OrganizationAuthenticationProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(StaticHeadersAuthenticationProvider.class);

  private final GatewayCredentials credentials;

  public StaticHeadersAuthenticationProvider(Map<String, String> headers) {
    this.credentials = new GatewayCredentials(headers);
    LOG.atInfo()
        .addKeyValue("headers", credentials.headers().keySet())
        .log("Static-header organization authentication configured");
  }

  @Override
  public GatewayCredentials getCredentials() {
    return credentials;
  }

  @Override
  public String toString() {
    return "StaticHeadersAuthenticationProvider{" + credentials + "}";
  }
}
