package com.barclays.groupcontrol.co.camunda.connectors.transport;

import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
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
  private final BamTokenCache tokenCache;
  private final boolean retryOnUnauthorized;

  public AuthenticatingSdkHttpClientBuilder(
      SdkHttpClient.Builder<?> delegate, BamTokenCache tokenCache, boolean retryOnUnauthorized) {
    this.delegate = delegate;
    this.tokenCache = tokenCache;
    this.retryOnUnauthorized = retryOnUnauthorized;
  }

  @Override
  public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
    final SdkHttpClient client =
        new AuthenticatingSdkHttpClient(
            delegate.buildWithDefaults(serviceDefaults), tokenCache, retryOnUnauthorized);
    LOG.atDebug()
        .addKeyValue("delegateClient", client.clientName())
        .addKeyValue("retryOnUnauthorized", retryOnUnauthorized)
        .log("Authenticating HTTP client for Barclays Bedrock gateway built");
    return client;
  }
}
