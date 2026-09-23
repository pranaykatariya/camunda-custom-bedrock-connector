package com.anthrobyte.camunda.aiagent.config;

import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.contextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.anthrobyte.camunda.aiagent.auth.AccessToken;
import com.anthrobyte.camunda.aiagent.auth.AccessTokenSource;
import com.anthrobyte.camunda.aiagent.auth.CachingTokenAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.GatewayCredentials;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.StaticHeadersAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.AiFrameworkAdapter;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

class OrganizationAuthAutoConfigurationTest {

  private static final String P = "organization.ai-gateway.auth.";
  private static final String SECRET = "super-secret-client-secret";
  private static final String[] OAUTH2 = {
    P + "enabled=true",
    P + "mode=OAUTH2_CLIENT_CREDENTIALS",
    P + "oauth2.token-uri=https://idp.example.com/oauth2/token",
    P + "oauth2.client-id=camunda",
    P + "oauth2.client-secret=" + SECRET
  };

  @Test
  void placeholderJwtIsTheDefaultModeAndSendsTheBamTokenHeader() {
    contextRunner()
        .withPropertyValues(P + "enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(ChatModelFactory.class);
              assertThat(ctx.getBean(ChatModelFactory.class)).isInstanceOf(OrganizationGatewayChatModelFactory.class);
              final var provider = ctx.getBean(OrganizationAuthenticationProvider.class);
              assertThat(provider).isInstanceOf(CachingTokenAuthenticationProvider.class);
              final var headers = provider.getCredentials().headers();
              assertThat(headers).containsKey("x-bam-token");
              assertThat(headers.get("x-bam-token")).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.");
              assertThat(ctx.getBean(OrganizationAuthProperties.class).mode())
                  .isEqualTo(OrganizationAuthProperties.Mode.PLACEHOLDER_JWT);
            });
  }

  @Test
  void oauth2ModeWiresTheCachingProvider() {
    contextRunner()
        .withPropertyValues(OAUTH2)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(OrganizationAuthenticationProvider.class))
                  .isInstanceOf(CachingTokenAuthenticationProvider.class);
              final var props = ctx.getBean(OrganizationAuthProperties.class);
              assertThat(props.toString()).doesNotContain(SECRET);
              assertThat(props.token().headerName()).isEqualTo("x-bam-token");
              assertThat(props.token().headerValueTemplate()).isEqualTo("{token}");
            });
  }

  @Test
  void staticHeadersMode() {
    contextRunner()
        .withPropertyValues(
            P + "enabled=true",
            P + "mode=STATIC_HEADERS",
            P + "static-headers[0].name=X-Client-ID",
            P + "static-headers[0].value=id",
            P + "static-headers[1].name=X-Client-Secret",
            P + "static-headers[1].value=" + SECRET)
        .run(
            ctx -> {
              final var provider = ctx.getBean(OrganizationAuthenticationProvider.class);
              assertThat(provider).isInstanceOf(StaticHeadersAuthenticationProvider.class);
              assertThat(provider.getCredentials().headers())
                  .containsEntry("X-Client-ID", "id")
                  .containsEntry("X-Client-Secret", SECRET);
              assertThat(ctx.getBean(OrganizationAuthProperties.class).toString()).doesNotContain(SECRET);
            });
  }

  static class CustomTokenSourceConfig {
    @Bean
    AccessTokenSource organizationJwt() {
      return () -> new AccessToken("custom-jwt", Instant.now().plus(Duration.ofMinutes(10)));
    }
  }

  @Test
  void customTokenSourceBeanIsCachedAndSentAsBamToken() {
    contextRunner()
        .withUserConfiguration(CustomTokenSourceConfig.class)
        .withPropertyValues(
            P + "enabled=true",
            P + "mode=CUSTOM",
            P + "static-headers[0].name=Accept",
            P + "static-headers[0].value=application/json")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              final var provider = ctx.getBean(OrganizationAuthenticationProvider.class);
              assertThat(provider).isInstanceOf(CachingTokenAuthenticationProvider.class);
              assertThat(provider.getCredentials().headers())
                  .containsEntry("x-bam-token", "custom-jwt")
                  .containsEntry("Accept", "application/json");
            });
  }

  static class CustomProviderConfig {
    @Bean
    OrganizationAuthenticationProvider customProvider() {
      return () -> GatewayCredentials.of("X-Proprietary-Signature", "sig");
    }
  }

  @Test
  void customProviderBeanReplacesBuiltInOne() {
    contextRunner()
        .withUserConfiguration(CustomProviderConfig.class)
        .withPropertyValues(P + "enabled=true", P + "mode=CUSTOM")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(OrganizationAuthenticationProvider.class);
              assertThat(ctx.getBean(OrganizationAuthenticationProvider.class).getCredentials().headers())
                  .containsKey("X-Proprietary-Signature");
            });
  }

  @Test
  void customModeWithoutBeanFailsFast() {
    contextRunner()
        .withPropertyValues(P + "enabled=true", P + "mode=CUSTOM")
        .run(
            ctx ->
                assertThat(rootMessage(ctx.getStartupFailure()))
                    .contains("mode=CUSTOM requires an AccessTokenSource or OrganizationAuthenticationProvider bean"));
  }

  @Test
  void invalidConfigurationFailsFastListingPropertiesAndEnvVarsButNotValues() {
    contextRunner()
        .withPropertyValues(
            P + "enabled=true",
            P + "mode=OAUTH2_CLIENT_CREDENTIALS",
            P + "oauth2.token-uri=https://user:" + SECRET + "@idp.example.com/token",
            P + "oauth2.client-id=",
            P + "oauth2.client-secret=",
            P + "token.header-value-template=Bearer",
            P + "static-headers[0].name=Host",
            P + "static-headers[0].value=https://gateway.example.com/path")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              final String message = rootMessage(ctx.getStartupFailure());
              assertThat(message)
                  .contains("oauth2.token-uri must not contain user-info")
                  .contains("oauth2.client-id is required (set ORG_AI_CLIENT_ID)")
                  .contains("oauth2.client-secret is required (set ORG_AI_CLIENT_SECRET)")
                  .contains("token.header-value-template must contain the placeholder {token}")
                  .contains("static-headers[0].value must be host[:port] for the Host header")
                  .doesNotContain(SECRET)
                  .doesNotContain("gateway.example.com/path");
            });
  }

  static class OtherFrameworkConfig {
    @Bean
    AiFrameworkAdapter<?> otherFrameworkAdapter() {
      return mock(AiFrameworkAdapter.class);
    }
  }

  @Test
  void inactiveWhenLangchain4jFrameworkIsNotUsed() {
    // With another framework Camunda expects the application to supply its own AiFrameworkAdapter;
    // the LangChain4j-specific customization must then stay out of the way.
    contextRunner()
        .withUserConfiguration(OtherFrameworkConfig.class)
        .withPropertyValues(OAUTH2)
        .withPropertyValues("camunda.connector.agenticai.framework=other")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(ChatModelFactory.class);
              assertThat(ctx).doesNotHaveBean(OrganizationGatewayChatModelFactory.class);
            });
  }

  @Test
  void propertiesToStringRedactsAllSecrets() {
    final var props =
        new OrganizationAuthProperties(
            true,
            OrganizationAuthProperties.Mode.STATIC_HEADERS,
            true,
            false,
            List.of(new OrganizationAuthProperties.Header("X-Client-Secret", SECRET)),
            new OrganizationAuthProperties.Token("x-bam-token", "{token}", Duration.ofSeconds(60), Duration.ofSeconds(15)),
            new OrganizationAuthProperties.PlaceholderJwt(Duration.ofMinutes(5)),
            new OrganizationAuthProperties.OAuth2(
                URI.create("https://idp"), "id", SECRET, null, null, null, null, null, null, null));

    assertThat(props.toString()).doesNotContain(SECRET).contains("X-Client-Secret");
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage();
  }
}
