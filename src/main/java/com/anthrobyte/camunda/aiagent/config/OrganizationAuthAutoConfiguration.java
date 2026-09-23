package com.anthrobyte.camunda.aiagent.config;

import com.anthrobyte.camunda.aiagent.auth.AccessTokenSource;
import com.anthrobyte.camunda.aiagent.auth.CachingTokenAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.PlaceholderJwtTokenSource;
import com.anthrobyte.camunda.aiagent.auth.StaticHeadersAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.oauth2.ClientCredentialsSettings;
import com.anthrobyte.camunda.aiagent.auth.oauth2.ClientCredentialsTokenClient;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationBedrockChatModelBuilder;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires organization authentication for the AWS Bedrock provider into the <b>unmodified</b>
 * Camunda AI Agent connector.
 *
 * <p><b>Integration mechanism.</b> Camunda's {@code AgenticAiLangchain4JFrameworkConfiguration}
 * (imported by {@link AgenticAiConnectorsAutoConfiguration}) declares {@code ChatModelFactory} as
 * {@code @Bean @ConditionalOnMissingBean}. This auto-configuration runs <i>before</i> Camunda's
 * ({@code before = AgenticAiConnectorsAutoConfiguration.class}) and registers {@link
 * OrganizationGatewayChatModelFactory} as the {@code ChatModelFactory}, so Camunda's default bean
 * backs off. Everything else (agent orchestration, tools, MCP, memory, prompts, job workers,
 * element templates) is Camunda's code and beans, untouched.
 *
 * <p>With {@code organization.ai-gateway.auth.enabled=false}, this class is inactive and the
 * runtime is exactly the standard connector.
 */
@AutoConfiguration(before = AgenticAiConnectorsAutoConfiguration.class)
@ConditionalOnBooleanProperty(OrganizationAuthProperties.PREFIX + ".enabled")
@ConditionalOnProperty(
    value = "camunda.connector.agenticai.framework",
    havingValue = "langchain4j",
    matchIfMissing = true)
@EnableConfigurationProperties(OrganizationAuthProperties.class)
public class OrganizationAuthAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(OrganizationAuthAutoConfiguration.class);

  /**
   * The credential source. Replace it by declaring your own {@link AccessTokenSource} bean (token
   * generation, cached here) or {@link OrganizationAuthenticationProvider} bean (everything), and
   * set {@code mode: CUSTOM}.
   */
  @Bean
  @ConditionalOnMissingBean
  public OrganizationAuthenticationProvider organizationAuthenticationProvider(
      OrganizationAuthProperties properties,
      AgenticAiHttpProxySupport agenticAiHttpProxySupport,
      ObjectProvider<AccessTokenSource> customTokenSource) {
    properties.validate();
    return switch (properties.mode()) {
      case PLACEHOLDER_JWT ->
          cachingProvider(
              properties,
              new PlaceholderJwtTokenSource(properties.placeholderJwt().lifetime(), Clock.systemUTC()),
              null);
      case OAUTH2_CLIENT_CREDENTIALS -> createOAuth2Provider(properties, agenticAiHttpProxySupport);
      case STATIC_HEADERS -> new StaticHeadersAuthenticationProvider(staticHeaders(properties));
      case CUSTOM -> {
        final AccessTokenSource source = customTokenSource.getIfAvailable();
        if (source == null) {
          throw new IllegalStateException(
              OrganizationAuthProperties.PREFIX
                  + ".mode=CUSTOM requires an AccessTokenSource or OrganizationAuthenticationProvider bean");
        }
        LOG.atInfo()
            .addKeyValue("tokenSource", source.getClass().getName())
            .log("Custom organization token source configured");
        yield cachingProvider(properties, source, null);
      }
    };
  }

  /**
   * Overrides Camunda's {@code ChatModelFactory} bean ({@code @ConditionalOnMissingBean} in {@code
   * AgenticAiLangchain4JFrameworkConfiguration#langchain4JChatModelFactory}).
   */
  @Bean
  public ChatModelFactory organizationGatewayChatModelFactory(
      AgenticAiConnectorsConfigurationProperties agenticAiProperties,
      ChatModelHttpProxySupport camundaChatModelHttpProxySupport,
      AgenticAiHttpProxySupport agenticAiHttpProxySupport,
      OrganizationAuthenticationProvider authenticationProvider,
      OrganizationAuthProperties properties) {
    properties.validate();
    LOG.atInfo()
        .addKeyValue("mode", properties.mode())
        .addKeyValue("staticHeaders", staticHeaders(properties).keySet())
        .log("Organization Bedrock gateway authentication enabled");
    warnIfHostHeaderIsPlaceholder(properties);

    // Identical to Camunda's default ChatModelFactory bean: used for every non-Bedrock provider.
    final ChatModelFactory standardFactory =
        new ChatModelFactoryImpl(agenticAiProperties, camundaChatModelHttpProxySupport);

    final var bedrockBuilder =
        new OrganizationBedrockChatModelBuilder(
            agenticAiProperties,
            agenticAiHttpProxySupport,
            authenticationProvider,
            properties.retryOnUnauthorized());

    LOG.atInfo()
        .addKeyValue("authenticationProvider", authenticationProvider.getClass().getName())
        .addKeyValue("supportsRefresh", authenticationProvider.supportsRefresh())
        .addKeyValue("retryOnUnauthorized", properties.retryOnUnauthorized())
        .log("Registering organization Bedrock ChatModelFactory (overrides Camunda default bean)");
    LOG.warn(
        "The Bedrock custom endpoint configured on an AI Agent element is used as it is: any URL "
            + "an element points at receives the organization credentials.");
    if (properties.allowInsecureHttp()) {
      LOG.warn(
          "organization.ai-gateway.auth.allow-insecure-http=true: the OAuth2 token URL may use "
              + "plain HTTP. Use for local development only.");
    }

    return new OrganizationGatewayChatModelFactory(standardFactory, bedrockBuilder);
  }

  private static OrganizationAuthenticationProvider cachingProvider(
      OrganizationAuthProperties properties, AccessTokenSource source, AutoCloseable resources) {
    final OrganizationAuthProperties.Token token = properties.token();
    LOG.atInfo()
        .addKeyValue("tokenSource", source.getClass().getSimpleName())
        .addKeyValue("headerName", token.headerName())
        .addKeyValue("additionalHeaders", staticHeaders(properties).keySet())
        .addKeyValue("refreshSkew", token.refreshSkew())
        .log("Token-based organization authentication configured");
    return new CachingTokenAuthenticationProvider(
        source,
        token.headerName(),
        token.headerValueTemplate(),
        staticHeaders(properties),
        token.refreshSkew(),
        token.refreshWaitTimeout(),
        Clock.systemUTC(),
        resources);
  }

  private static OrganizationAuthenticationProvider createOAuth2Provider(
      OrganizationAuthProperties properties, AgenticAiHttpProxySupport agenticAiHttpProxySupport) {
    final OrganizationAuthProperties.OAuth2 oauth2 = properties.oauth2();

    final HttpClient.Builder httpClientBuilder =
        HttpClient.newBuilder()
            .connectTimeout(oauth2.connectTimeout())
            // Never follow redirects: the client secret must only ever reach the configured host.
            .followRedirects(HttpClient.Redirect.NEVER);
    // Honour the same CONNECTOR_HTTP(S)_PROXY_* settings the AI Agent uses.
    agenticAiHttpProxySupport.getJdkHttpClientProxyConfigurator().configure(httpClientBuilder);
    final HttpClient tokenHttpClient = httpClientBuilder.build();

    final var settings =
        new ClientCredentialsSettings(
            oauth2.tokenUri(),
            oauth2.clientId(),
            oauth2.clientSecret(),
            oauth2.scope(),
            oauth2.audience(),
            oauth2.clientAuthentication(),
            oauth2.additionalParameters(),
            oauth2.requestTimeout(),
            oauth2.defaultTokenLifetime());

    LOG.atInfo()
        .addKeyValue("tokenEndpoint", oauth2.tokenUri().getHost())
        .addKeyValue("clientId", oauth2.clientId())
        .addKeyValue("clientAuthentication", oauth2.clientAuthentication())
        .addKeyValue("scope", oauth2.scope())
        .addKeyValue("audience", oauth2.audience())
        .addKeyValue("connectTimeout", oauth2.connectTimeout())
        .addKeyValue("requestTimeout", oauth2.requestTimeout())
        .log("OAuth2 client-credentials organization authentication configured");

    final var tokenClient =
        new ClientCredentialsTokenClient(
            settings, tokenHttpClient, new ObjectMapper(), Clock.systemUTC());
    return cachingProvider(properties, tokenClient, tokenHttpClient);
  }

  /**
   * application.yml ships a {@code .invalid} Host header as a placeholder. It passes validation,
   * so without this warning the first sign would be the gateway failing to route requests.
   */
  private static void warnIfHostHeaderIsPlaceholder(OrganizationAuthProperties properties) {
    properties.staticHeaders().stream()
        .filter(h -> "host".equalsIgnoreCase(h.name()))
        .filter(h -> h.value() != null && h.value().toLowerCase(Locale.ROOT).endsWith(".invalid"))
        .findFirst()
        .ifPresent(
            h ->
                LOG.warn(
                    "The Host header sent to the Bedrock gateway is still the placeholder from "
                        + "application.yml. Set ORG_AI_GATEWAY_HOST_HEADER to the host the gateway routes on."));
  }

  private static Map<String, String> staticHeaders(OrganizationAuthProperties properties) {
    final Map<String, String> headers = new LinkedHashMap<>();
    properties.staticHeaders().forEach(h -> headers.put(h.name(), h.value()));
    return headers;
  }
}
