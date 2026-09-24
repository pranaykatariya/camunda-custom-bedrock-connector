package com.barclays.groupcontrol.co.camunda.connectors.config;

import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenClient;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenSettings;
import com.barclays.groupcontrol.co.camunda.connectors.camunda.BarclaysBedrockChatModelBuilder;
import com.barclays.groupcontrol.co.camunda.connectors.camunda.BarclaysGatewayChatModelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactoryImpl;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelHttpProxySupport;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsAutoConfiguration;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties;
import io.camunda.connector.agenticai.common.AgenticAiHttpProxySupport;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires Barclays authentication for the AWS Bedrock provider into the <b>unmodified</b>
 * Camunda AI Agent connector.
 *
 * <p><b>Integration mechanism.</b> Camunda's {@code AgenticAiLangchain4JFrameworkConfiguration}
 * (imported by {@link AgenticAiConnectorsAutoConfiguration}) declares {@code ChatModelFactory} as
 * {@code @Bean @ConditionalOnMissingBean}. This auto-configuration runs <i>before</i> Camunda's
 * ({@code before = AgenticAiConnectorsAutoConfiguration.class}) and registers {@link
 * BarclaysGatewayChatModelFactory} as the {@code ChatModelFactory}, so Camunda's default bean
 * backs off. Everything else (agent orchestration, tools, MCP, memory, prompts, job workers,
 * element templates) is Camunda's code and beans, untouched.
 *
 * <p>With {@code barclays.ai-gateway.auth.enabled=false}, this class is inactive and the
 * runtime is exactly the standard connector.
 */
@AutoConfiguration(before = AgenticAiConnectorsAutoConfiguration.class)
@ConditionalOnBooleanProperty(BarclaysAuthProperties.PREFIX + ".enabled")
@ConditionalOnProperty(
    value = "camunda.connector.agenticai.framework",
    havingValue = "langchain4j",
    matchIfMissing = true)
@EnableConfigurationProperties(BarclaysAuthProperties.class)
public class BarclaysAuthAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BarclaysAuthAutoConfiguration.class);

  /**
   * The Barclays credentials: a BAM token from {@link BamTokenClient}, cached by {@link
   * BamTokenCache} until {@code token.refresh-skew} before it expires. Closed (with its HTTP client)
   * when the context shuts down.
   */
  @Bean
  public BamTokenCache bamTokenCache(
      BarclaysAuthProperties properties, AgenticAiHttpProxySupport agenticAiHttpProxySupport) {
    properties.validate();
    final BarclaysAuthProperties.Bam bam = properties.bam();
    final Map<String, String> staticHeaders = staticHeaders(properties);

    final HttpClient.Builder httpClientBuilder =
        HttpClient.newBuilder()
            .connectTimeout(bam.connectTimeout())
            // Never follow redirects: the credentials must only ever reach the configured host.
            .followRedirects(HttpClient.Redirect.NEVER);
    // Honour the same CONNECTOR_HTTP(S)_PROXY_* settings the AI Agent uses.
    agenticAiHttpProxySupport.getJdkHttpClientProxyConfigurator().configure(httpClientBuilder);
    final HttpClient tokenHttpClient = httpClientBuilder.build();

    final var tokenClient =
        new BamTokenClient(
            new BamTokenSettings(
                bam.tokenUrl(),
                bam.username(),
                bam.password(),
                bam.requestTimeout(),
                bam.defaultTokenLifetime()),
            tokenHttpClient,
            new ObjectMapper(),
            Clock.systemUTC());

    final BarclaysAuthProperties.Token token = properties.token();
    LOG.atInfo()
        .addKeyValue("tokenEndpoint", bam.tokenUrl().getHost())
        .addKeyValue("headerName", token.headerName())
        .addKeyValue("additionalHeaders", staticHeaders.keySet())
        .addKeyValue("refreshSkew", token.refreshSkew())
        .addKeyValue("connectTimeout", bam.connectTimeout())
        .addKeyValue("requestTimeout", bam.requestTimeout())
        .log("Token-based Barclays authentication configured");
    return new BamTokenCache(
        tokenClient,
        token.headerName(),
        token.headerValueTemplate(),
        staticHeaders,
        token.refreshSkew(),
        token.refreshWaitTimeout(),
        Clock.systemUTC());
  }

  /**
   * Overrides Camunda's {@code ChatModelFactory} bean ({@code @ConditionalOnMissingBean} in {@code
   * AgenticAiLangchain4JFrameworkConfiguration#langchain4JChatModelFactory}).
   */
  @Bean
  public ChatModelFactory barclaysGatewayChatModelFactory(
      AgenticAiConnectorsConfigurationProperties agenticAiProperties,
      ChatModelHttpProxySupport camundaChatModelHttpProxySupport,
      AgenticAiHttpProxySupport agenticAiHttpProxySupport,
      BamTokenCache tokenCache,
      BarclaysAuthProperties properties) {
    // Already validated by bamTokenCache(), which this bean depends on and so is created first.
    LOG.atInfo()
        .addKeyValue("staticHeaders", staticHeaders(properties).keySet())
        .log("Barclays Bedrock gateway authentication enabled");
    warnIfHostHeaderIsPlaceholder(properties);

    // Identical to Camunda's default ChatModelFactory bean: used for every non-Bedrock provider.
    final ChatModelFactory standardFactory =
        new ChatModelFactoryImpl(agenticAiProperties, camundaChatModelHttpProxySupport);

    final var bedrockBuilder =
        new BarclaysBedrockChatModelBuilder(
            agenticAiProperties,
            agenticAiHttpProxySupport,
            tokenCache,
            properties.retryOnUnauthorized());

    LOG.atInfo()
        .addKeyValue("retryOnUnauthorized", properties.retryOnUnauthorized())
        .log("Registering Barclays Bedrock ChatModelFactory (overrides Camunda default bean)");
    LOG.warn(
        "The Bedrock custom endpoint configured on an AI Agent element is used as it is: any URL "
            + "an element points at receives the Barclays credentials.");
    if (properties.allowInsecureHttp()) {
      LOG.warn(
          "barclays.ai-gateway.auth.allow-insecure-http=true: the BAM token URL may use plain "
              + "HTTP. Use for local development only.");
    }

    return new BarclaysGatewayChatModelFactory(standardFactory, bedrockBuilder);
  }

  /**
   * application.yml ships a {@code .invalid} Host header as a placeholder. It passes validation,
   * so without this warning the first sign would be the gateway failing to route requests.
   */
  private static void warnIfHostHeaderIsPlaceholder(BarclaysAuthProperties properties) {
    properties.staticHeaders().stream()
        .filter(h -> "host".equalsIgnoreCase(h.name()))
        .filter(h -> h.value() != null && h.value().toLowerCase(Locale.ROOT).endsWith(".invalid"))
        .findFirst()
        .ifPresent(
            h ->
                LOG.warn(
                    "The Host header sent to the Bedrock gateway is still the placeholder from "
                        + "application.yml. Set BARCLAYS_AI_GATEWAY_HOST_HEADER to the host the gateway routes on."));
  }

  private static Map<String, String> staticHeaders(BarclaysAuthProperties properties) {
    final Map<String, String> headers = new LinkedHashMap<>();
    properties.staticHeaders().forEach(h -> headers.put(h.name(), h.value()));
    return headers;
  }
}
