package com.anthrobyte.camunda.aiagent.transport;

import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.utils.AttributeMap;

/**
 * Wraps an {@link SdkHttpClient.Builder} (the Apache builder configured like Camunda's) so that the
 * client it builds is an {@link AuthenticatingSdkHttpClient}.
 *
 * <p>It is handed to {@code BedrockRuntimeClientBuilder#httpClientBuilder}, not {@code
 * #httpClient}, so the AWS SDK owns the built client and closes it when the {@code
 * BedrockRuntimeClient} is closed, exactly as with Camunda's own builder.
 */
public final class AuthenticatingSdkHttpClientBuilder
    implements SdkHttpClient.Builder<AuthenticatingSdkHttpClientBuilder> {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticatingSdkHttpClientBuilder.class);

  private final SdkHttpClient.Builder<?> delegate;
  private final OrganizationAuthenticationProvider authenticationProvider;
  private final GatewayEndpointMatcher endpointMatcher;
  private final boolean retryOnUnauthorized;

  public AuthenticatingSdkHttpClientBuilder(
      SdkHttpClient.Builder<?> delegate,
      OrganizationAuthenticationProvider authenticationProvider,
      GatewayEndpointMatcher endpointMatcher,
      boolean retryOnUnauthorized) {
    this.delegate = delegate;
    this.authenticationProvider = authenticationProvider;
    this.endpointMatcher = endpointMatcher;
    this.retryOnUnauthorized = retryOnUnauthorized;
  }

  @Override
  public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
    final SdkHttpClient client =
        new AuthenticatingSdkHttpClient(
            delegate.buildWithDefaults(serviceDefaults),
            authenticationProvider,
            endpointMatcher,
            retryOnUnauthorized);
    LOG.atDebug()
        .addKeyValue("delegateClient", client.clientName())
        .addKeyValue("allowedEndpoints", endpointMatcher)
        .addKeyValue("retryOnUnauthorized", retryOnUnauthorized)
        .log("Authenticating HTTP client for organization Bedrock gateway built");
    return client;
  }
}
