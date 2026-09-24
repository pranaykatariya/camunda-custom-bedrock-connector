package com.barclays.groupcontrol.co.camunda.connectors.camunda;

import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.ENDPOINT_NOT_CONFIGURED;

import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BarclaysAuthenticationException;
import com.barclays.groupcontrol.co.camunda.connectors.transport.AuthenticatingSdkHttpClientBuilder;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModel;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModelDelegate;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication.AwsDefaultCredentialsChainAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockConnection;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties;
import io.camunda.connector.agenticai.common.AgenticAiHttpProxySupport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.auth.scheme.NoAuthAuthScheme;
import software.amazon.awssdk.http.auth.spi.scheme.AuthSchemeOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.auth.scheme.BedrockRuntimeAuthSchemeProvider;

/**
 * Builds the Bedrock chat model for the Barclays gateway (HEADER mode).
 *
 * <p>The client is configured exactly like Camunda's {@code ChatModelFactoryImpl#createBedrockClient}
 * (see {@link CamundaBedrockClientParity}), with two differences:
 *
 * <ol>
 *   <li><b>Authentication.</b> The element's AWS authentication is ignored. The client never signs
 *       (anonymous credentials plus the no-auth scheme, as in Camunda's own API-key mode), and
 *       {@link com.barclays.groupcontrol.co.camunda.connectors.transport.AuthenticatingSdkHttpClient} adds the
 *       Barclays headers to every HTTP attempt.
 *   <li><b>Endpoint.</b> The element's custom endpoint is required and is used exactly as
 *       configured: any absolute http(s) URL is accepted. Without one the job fails closed; it
 *       never falls back to AWS or to Camunda's built-in Bedrock authentication.
 * </ol>
 */
public class BarclaysBedrockChatModelBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(BarclaysBedrockChatModelBuilder.class);

  /** Resolves only the no-auth scheme, so no SigV4 or bearer signer can ever run. */
  private static final BedrockRuntimeAuthSchemeProvider NO_AUTH_ONLY =
      params -> List.of(AuthSchemeOption.builder().schemeId(NoAuthAuthScheme.SCHEME_ID).build());

  private final Duration defaultTimeout;
  private final AgenticAiHttpProxySupport proxySupport;
  private final BamTokenCache tokenCache;
  private final boolean retryOnUnauthorized;

  public BarclaysBedrockChatModelBuilder(
      AgenticAiConnectorsConfigurationProperties agenticAiProperties,
      AgenticAiHttpProxySupport proxySupport,
      BamTokenCache tokenCache,
      boolean retryOnUnauthorized) {
    this.defaultTimeout = agenticAiProperties.aiagent().chatModel().api().defaultTimeout();
    this.proxySupport = proxySupport;
    this.tokenCache = tokenCache;
    this.retryOnUnauthorized = retryOnUnauthorized;
  }

  public CloseableChatModel create(BedrockProviderConfiguration configuration) {
    final BedrockConnection connection = configuration.bedrock();
    final URI endpoint = gatewayEndpoint(connection.endpoint());
    warnIfElementCredentialsAreIgnored(connection.authentication());
    final Duration timeout = CamundaBedrockClientParity.deriveTimeout(connection.timeouts(), defaultTimeout);

    LOG.atInfo()
        .addKeyValue("gatewayHost", endpoint.getHost())
        .addKeyValue("gatewayPath", endpoint.getRawPath())
        .addKeyValue("region", connection.region())
        .addKeyValue("model", connection.model() == null ? null : connection.model().model())
        .addKeyValue("timeout", timeout)
        .addKeyValue("timeoutSource", isElementTimeout(connection, timeout) ? "element" : "default")
        .log("Creating Bedrock chat model for Barclays gateway");

    BedrockRuntimeClient client = null;
    try {
      client = createClient(connection, endpoint, timeout);
      final BedrockChatModel.Builder modelBuilder =
          BedrockChatModel.builder()
              .client(client)
              .modelId(connection.model().model())
              .timeout(timeout);
      CamundaBedrockClientParity.applyModelParameters(connection, modelBuilder);
      final var chatModel =
          new BarclaysAuthenticatedChatModel(modelBuilder.build(), tokenCache);
      return new CloseableChatModelDelegate(chatModel, client);
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("gatewayHost", endpoint.getHost())
          .addKeyValue("error", e.getClass().getSimpleName())
          .log("Creating Bedrock chat model for Barclays gateway failed");
      closeQuietly(client);
      throw e;
    }
  }

  private static boolean isElementTimeout(BedrockConnection connection, Duration timeout) {
    return connection.timeouts() != null && timeout.equals(connection.timeouts().timeout());
  }

  private BedrockRuntimeClient createClient(
      BedrockConnection connection, URI endpoint, Duration timeout) {
    return BedrockRuntimeClient.builder()
        .region(Region.of(connection.region()))
        .endpointOverride(endpoint)
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .putAuthScheme(NoAuthAuthScheme.create())
        .authSchemeProvider(NO_AUTH_ONLY)
        .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(timeout).build())
        .httpClientBuilder(
            new AuthenticatingSdkHttpClientBuilder(
                CamundaBedrockClientParity.apacheHttpClientBuilder(
                    proxySupport.getProxyConfiguration(), endpoint, timeout),
                tokenCache,
                retryOnUnauthorized))
        .build();
  }

  /**
   * The element's custom endpoint. It is the gateway URL: whatever is configured on the element is
   * used, with no allow list and no scheme restriction. Only a missing or unusable URL fails.
   */
  private URI gatewayEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw endpointRejected(null, "custom endpoint is not set");
    }
    final URI uri;
    try {
      uri = URI.create(endpoint.trim());
    } catch (IllegalArgumentException e) {
      throw endpointRejected(null, "custom endpoint is not a valid url");
    }
    if (!uri.isAbsolute() || uri.getHost() == null) {
      throw endpointRejected(uri, "custom endpoint is not an absolute url with a host");
    }
    LOG.atDebug()
        .addKeyValue("endpointHost", uri.getHost())
        .addKeyValue("endpointPath", uri.getRawPath())
        .log("Bedrock custom endpoint resolved");
    return uri;
  }

  /** Logs scheme, host, port and path only, never user-info, so an operator can spot the typo. */
  private BarclaysAuthenticationException endpointRejected(URI endpoint, String detail) {
    LOG.atWarn()
        .addKeyValue("endpointScheme", endpoint == null ? null : endpoint.getScheme())
        .addKeyValue("endpointHost", endpoint == null ? null : endpoint.getHost())
        .addKeyValue("endpointPort", endpoint == null || endpoint.getPort() == -1 ? null : endpoint.getPort())
        .addKeyValue("endpointPath", endpoint == null ? null : endpoint.getRawPath())
        .addKeyValue("problem", detail)
        .log("Rejecting Bedrock endpoint: no usable Barclays gateway URL on the element");
    return new BarclaysAuthenticationException(ENDPOINT_NOT_CONFIGURED, null, detail);
  }

  private static void warnIfElementCredentialsAreIgnored(AwsAuthentication authentication) {
    if (authentication != null && !(authentication instanceof AwsDefaultCredentialsChainAuthentication)) {
      LOG.atWarn()
          .addKeyValue("elementAuthentication", authentication.getClass().getSimpleName())
          .log(
              "AWS credentials are configured on the AI Agent element. They are ignored: the "
                  + "runtime authenticates to the Bedrock gateway with Barclays credentials.");
    }
  }

  private static void closeQuietly(BedrockRuntimeClient client) {
    if (client != null) {
      try {
        client.close();
      } catch (RuntimeException e) {
        LOG.atDebug().addKeyValue("error", e.getClass().getSimpleName()).log("Closing Bedrock client failed");
      }
    }
  }
}
