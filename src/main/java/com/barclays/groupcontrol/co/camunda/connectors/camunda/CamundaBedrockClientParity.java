package com.barclays.groupcontrol.co.camunda.connectors.camunda;

import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockConnection;
import io.camunda.connector.agenticai.aiagent.model.request.provider.shared.TimeoutConfiguration;
import io.camunda.connector.http.client.proxy.NonProxyHosts;
import io.camunda.connector.http.client.proxy.ProxyConfiguration.ProxyDetails;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;

/**
 * <b>Everything this project copies from Camunda's Bedrock client setup, in one place.</b>
 *
 * <p>Source: {@code io.camunda.connector:connector-agentic-ai:8.9.12}, decompiled
 * {@code ChatModelFactoryImpl#createBedrockClient}, {@code #createBedrockChatModel},
 * {@code #deriveTimeoutSetting}, {@code #applyBedrockModelParametersIfPresent} (all private) and
 * {@code ChatModelHttpProxySupport#createAwsHttpClientBuilder} / {@code #createAwsProxyConfiguration}
 * (package-private). They cannot be called from here, so their logic is reproduced line by line.
 *
 * <p><b>On every connectors upgrade</b>, diff these methods against the new Camunda version
 * (docs/UPGRADING.md). {@code BarclaysBedrockChatModelBuilderTest#requestBodyIsIdenticalToStandardConnector}
 * compares the request body with the one Camunda's own factory sends.
 */
final class CamundaBedrockClientParity {

  private static final Logger LOG = LoggerFactory.getLogger(CamundaBedrockClientParity.class);

  /** {@code ChatModelFactoryImpl.CONNECT_TIMEOUT} ({@code Duration.ofSeconds(15)}). */
  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(15);

  /** Scheme {@code createAwsHttpClientBuilder} assumes when no endpoint override is set. */
  static final String DEFAULT_SCHEME = "https";

  private CamundaBedrockClientParity() {}

  /**
   * {@code ChatModelFactoryImpl#deriveTimeoutSetting}: the element timeout if it is positive,
   * otherwise {@code camunda.connector.agenticai.aiagent.chat-model.api.default-timeout}.
   */
  static Duration deriveTimeout(TimeoutConfiguration timeouts, Duration defaultTimeout) {
    return Optional.ofNullable(timeouts)
        .map(TimeoutConfiguration::timeout)
        .filter(Duration::isPositive)
        .orElse(defaultTimeout);
  }

  /**
   * {@code ChatModelHttpProxySupport#createAwsHttpClientBuilder(URI)} followed by the {@code
   * connectionTimeout} / {@code socketTimeout} calls in {@code createBedrockClient}.
   */
  static ApacheHttpClient.Builder apacheHttpClientBuilder(
      io.camunda.connector.http.client.proxy.ProxyConfiguration proxyConfiguration,
      URI endpoint,
      Duration timeout) {
    final String scheme = endpoint != null ? endpoint.getScheme() : DEFAULT_SCHEME;
    return ApacheHttpClient.builder()
        .proxyConfiguration(awsProxyConfiguration(proxyConfiguration, scheme))
        .connectionTimeout(CONNECTION_TIMEOUT)
        .socketTimeout(timeout);
  }

  /** {@code ChatModelHttpProxySupport#createAwsProxyConfiguration(String)}. */
  static ProxyConfiguration awsProxyConfiguration(
      io.camunda.connector.http.client.proxy.ProxyConfiguration proxyConfiguration, String scheme) {
    final ProxyConfiguration.Builder builder =
        ProxyConfiguration.builder().useSystemPropertyValues(Boolean.TRUE);
    proxyConfiguration
        .getProxyDetails(scheme)
        .ifPresentOrElse(
            details -> applyProxyDetails(builder, scheme, details),
            () ->
                LOG.debug(
                    "No connector proxy for target scheme [{}]; JVM proxy system properties still apply",
                    scheme));
    return builder.build();
  }

  private static void applyProxyDetails(
      ProxyConfiguration.Builder builder, String targetScheme, ProxyDetails details) {
    LOG.debug(
        "Using proxy for target scheme [{}] => [{}://{}:{}]",
        targetScheme,
        details.scheme(),
        details.host(),
        details.port());
    builder.scheme(details.scheme());
    // ChatModelHttpProxySupport#toUri
    builder.endpoint(URI.create(details.scheme() + "://" + details.host() + ":" + details.port()));
    builder.nonProxyHosts(NonProxyHosts.getNonProxyHostRegexPatterns().collect(Collectors.toSet()));
    if (details.hasCredentials()) {
      builder.username(details.user());
      builder.password(details.password());
    }
  }

  /**
   * {@code ChatModelFactoryImpl#applyBedrockModelParametersIfPresent}: maxTokens becomes
   * maxOutputTokens; temperature and topP are set only when present.
   */
  static void applyModelParameters(BedrockConnection connection, BedrockChatModel.Builder builder) {
    final var parameters = connection.model().parameters();
    if (parameters == null) {
      return;
    }
    final var requestParameters = BedrockChatRequestParameters.builder();
    Optional.ofNullable(parameters.maxTokens()).ifPresent(requestParameters::maxOutputTokens);
    Optional.ofNullable(parameters.temperature()).ifPresent(requestParameters::temperature);
    Optional.ofNullable(parameters.topP()).ifPresent(requestParameters::topP);
    builder.defaultRequestParameters(requestParameters.build());
    LOG.atDebug()
        .addKeyValue("maxOutputTokens", parameters.maxTokens())
        .addKeyValue("temperature", parameters.temperature())
        .addKeyValue("topP", parameters.topP())
        .log("Bedrock model parameters applied from the AI Agent element");
  }
}
