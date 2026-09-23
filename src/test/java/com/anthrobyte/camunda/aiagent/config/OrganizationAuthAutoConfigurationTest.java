package com.anthrobyte.camunda.aiagent.config;

import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.contextRunner;
import static com.anthrobyte.camunda.aiagent.support.BamResponses.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.anthrobyte.camunda.aiagent.auth.BamTokenCache;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
import com.anthrobyte.camunda.aiagent.support.BamResponses;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import io.camunda.connector.agenticai.aiagent.framework.AiFrameworkAdapter;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

class OrganizationAuthAutoConfigurationTest {

  private static final String P = "organization.ai-gateway.auth.";
  private static final String SECRET = "super-secret-client-secret";

  private final FakeHttpServer bam = new FakeHttpServer().on(TOKEN_PATH, BamResponses.issuing());

  @AfterEach
  void tearDown() {
    bam.close();
  }

  @Test
  void bamTokenIsFetchedCachedAndSentAsBamToken() {
    contextRunner()
        .withPropertyValues(BamResponses.properties(bam))
        .withPropertyValues(
            P + "enabled=true",
            P + "static-headers[0].name=Accept",
            P + "static-headers[0].value=application/json")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(ChatModelFactory.class);
              assertThat(ctx.getBean(ChatModelFactory.class)).isInstanceOf(OrganizationGatewayChatModelFactory.class);
              final var props = ctx.getBean(OrganizationAuthProperties.class);
              assertThat(props.token().headerName()).isEqualTo("x-bam-token");
              assertThat(props.token().headerValueTemplate()).isEqualTo("{token}");
              assertThat(props.toString()).doesNotContain(BamResponses.PASSWORD);
              // lazy: nothing is fetched at startup
              assertThat(bam.callCount(TOKEN_PATH)).isZero();

              final var cache = ctx.getBean(BamTokenCache.class);
              assertThat(cache.getCredentials().headers())
                  .containsEntry("x-bam-token", BamResponses.jwt(1))
                  .containsEntry("Accept", "application/json");
              assertThat(cache.getCredentials()).isSameAs(cache.getCredentials());
              assertThat(bam.callCount(TOKEN_PATH)).isEqualTo(1);
            });
  }

  @Test
  void legacyModePropertyFromExistingDeploymentsIsIgnored() {
    // Existing deployments still set ORGANIZATION_AIGATEWAY_AUTH_MODE; it no longer has any effect.
    contextRunner()
        .withPropertyValues(BamResponses.properties(bam))
        .withPropertyValues(P + "enabled=true", P + "mode=PLACEHOLDER_JWT")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(BamTokenCache.class).getCredentials().headers())
                  .containsEntry("x-bam-token", BamResponses.jwt(1));
            });
  }

  @Test
  void missingBamSecretsFailStartupNamingTheEnvironmentVariables() {
    contextRunner()
        .withPropertyValues(P + "enabled=true")
        .run(
            ctx ->
                assertThat(rootMessage(ctx.getStartupFailure()))
                    .contains("bam.token-url is required (set ORG_AI_BAM_TOKEN_URL)")
                    .contains("bam.username is required (set ORG_AI_BAM_USERNAME)")
                    .contains("bam.password is required (set ORG_AI_BAM_PASSWORD)"));
  }

  @Test
  void bamTokenUrlMustBeHttpsWithoutUserInfo() {
    contextRunner()
        .withPropertyValues(
            P + "enabled=true",
            P + "bam.token-url=http://user:" + SECRET + "@bam.example.com/api/token",
            P + "bam.username=bam:user",
            P + "bam.password=" + SECRET)
        .run(
            ctx ->
                assertThat(rootMessage(ctx.getStartupFailure()))
                    .contains("bam.token-url must use https")
                    .contains("bam.token-url must not contain user-info")
                    .contains("bam.username must not contain ':'")
                    .doesNotContain(SECRET));
  }

  @Test
  void invalidConfigurationFailsFastListingPropertiesAndEnvVarsButNotValues() {
    contextRunner()
        .withPropertyValues(BamResponses.properties(bam))
        .withPropertyValues(
            P + "enabled=true",
            P + "token.header-name=bad header",
            P + "token.header-value-template=Bearer",
            P + "static-headers[0].name=Host",
            P + "static-headers[0].value=https://gateway.example.com/path")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              final String message = rootMessage(ctx.getStartupFailure());
              assertThat(message)
                  .contains("token.header-name must be a valid HTTP header name (check ORG_AI_GATEWAY_TOKEN_HEADER)")
                  .contains("token.header-value-template must contain the placeholder {token}")
                  .contains("static-headers[0].value must be host[:port] for the Host header")
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
        .withPropertyValues(BamResponses.properties(bam))
        .withPropertyValues(P + "enabled=true")
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
            true,
            List.of(new OrganizationAuthProperties.Header("X-Client-Secret", SECRET)),
            false,
            new OrganizationAuthProperties.Token("x-bam-token", "{token}", Duration.ofSeconds(60), Duration.ofSeconds(15)),
            new OrganizationAuthProperties.Bam(
                URI.create("https://bam.example.com/api/token"), "bam-user", SECRET, null, null, null));

    assertThat(props.toString()).doesNotContain(SECRET).doesNotContain("bam-user").contains("X-Client-Secret");
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage();
  }
}
