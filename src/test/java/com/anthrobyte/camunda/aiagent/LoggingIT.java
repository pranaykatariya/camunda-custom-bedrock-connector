package com.anthrobyte.camunda.aiagent;

import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.contextRunner;
import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.outboundContext;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.BEDROCK_MODEL;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.BEDROCK_REGION;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.anthrobyte.camunda.aiagent.support.BedrockResponses;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import com.anthrobyte.camunda.aiagent.support.TokenResponses;
import io.camunda.connector.agenticai.aiagent.AiAgentFunction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Runs the AI Agent with this project's loggers at TRACE and checks that the operational log
 * statements appear and that no credential ever does, at any level.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingIT {

  private static final String TOKEN_PATH = "/oauth2/token";
  private static final String CONVERSE = "/bedrock" + BedrockResponses.conversePath(BEDROCK_MODEL);
  private static final String CLIENT_ID = "logging-client";
  private static final String CLIENT_SECRET = "Log-Secret-Value-123";
  private static final String TOKEN_PREFIX = "AccessTokenValue";

  private final FakeHttpServer idp = new FakeHttpServer();
  private final FakeHttpServer gateway = new FakeHttpServer();
  private final Logger projectLogger =
      (Logger) LoggerFactory.getLogger("com.anthrobyte.camunda.aiagent");
  private Level previousLevel;

  @BeforeEach
  void traceLogging() {
    previousLevel = projectLogger.getLevel();
    projectLogger.setLevel(Level.TRACE);
    idp.on(TOKEN_PATH, (req, n) -> Response.json(200, TokenResponses.token(TOKEN_PREFIX + n, 3600)));
  }

  @AfterEach
  void tearDown() {
    projectLogger.setLevel(previousLevel);
    idp.close();
    gateway.close();
  }

  private Map<String, Object> inputs(String endpoint) {
    final Map<String, Object> bedrock = new HashMap<>();
    bedrock.put("region", BEDROCK_REGION);
    bedrock.put("endpoint", endpoint);
    // Leftover AWS keys on the element: must be ignored, never sent or logged
    bedrock.put(
        "authentication",
        Map.of("type", "credentials", "accessKey", "AKIA-Bpmn-Access-Key", "secretKey", "Bpmn-Secret-Key-Value"));
    bedrock.put("model", Map.of("model", BEDROCK_MODEL));
    final Map<String, Object> data = new HashMap<>();
    data.put("systemPrompt", Map.of("prompt", "You are helpful."));
    data.put("userPrompt", Map.of("prompt", "Hi"));
    data.put("memory", Map.of("storage", Map.of("type", "in-process"), "contextWindowSize", 10));
    data.put("limits", Map.of("maxModelCalls", 5));
    data.put("response", Map.of("format", Map.of("type", "text")));
    return Map.of("provider", Map.of("type", "bedrock", "bedrock", bedrock), "data", data);
  }

  private void runAgent(String endpoint) {
    contextRunner()
        .withPropertyValues(
            "organization.ai-gateway.auth.enabled=true",
            "organization.ai-gateway.auth.allow-insecure-http=true",
            "organization.ai-gateway.auth.allowed-endpoints=" + gateway.baseUrl() + "/bedrock",
            "organization.ai-gateway.auth.mode=OAUTH2_CLIENT_CREDENTIALS",
            "organization.ai-gateway.auth.oauth2.token-uri=" + idp.baseUrl() + TOKEN_PATH,
            "organization.ai-gateway.auth.oauth2.client-id=" + CLIENT_ID,
            "organization.ai-gateway.auth.oauth2.client-secret=" + CLIENT_SECRET)
        .run(
            ctx -> {
              try {
                ctx.getBean(AiAgentFunction.class).execute(outboundContext(inputs(endpoint)));
              } catch (RuntimeException expectedInFailureScenarios) {
                // asserted by the caller through the captured log
              }
            });
  }

  private void runAgent() {
    runAgent(gateway.baseUrl() + "/bedrock");
  }

  private static void assertNoSecrets(CapturedOutput output) {
    final String basic =
        Base64.getEncoder()
            .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    assertThat(output.getAll())
        .doesNotContain(CLIENT_SECRET)
        .doesNotContain(basic)
        .doesNotContain(TOKEN_PREFIX)
        .doesNotContain("AKIA-Bpmn-Access-Key")
        .doesNotContain("Bpmn-Secret-Key-Value");
  }

  @Test
  void happyPathWithTokenRefreshAfter401(CapturedOutput output) {
    gateway.on(
        CONVERSE,
        (req, n) -> n == 1 ? Response.json(401, "{}") : Response.json(200, BedrockResponses.text("ok")));

    runAgent();

    assertThat(output.getAll())
        // startup
        .contains("Organization Bedrock gateway authentication enabled")
        .contains("OAuth2 client-credentials organization authentication configured")
        .contains("Token-based organization authentication configured")
        .contains("Registering organization Bedrock ChatModelFactory")
        .contains("Camunda ChatModelFactory replaced by organization Bedrock gateway router")
        // per request
        .contains("Creating Bedrock chat model for organization gateway")
        .contains("AWS credentials are configured on the AI Agent element")
        .contains("Authenticating HTTP client for organization Bedrock gateway built")
        .contains("Organization authentication token refresh required")
        .contains("Requesting organization authentication token")
        .contains("Organization authentication token received")
        .contains("Organization authentication token refreshed")
        .contains("Calling organization Bedrock gateway")
        .contains("Organization Bedrock gateway returned an error status")
        .contains("refreshing organization credentials and retrying once")
        .contains("Organization authentication token invalidated after gateway rejection")
        .contains("Organization Bedrock gateway call succeeded")
        .contains("Retry with refreshed organization credentials completed")
        .contains("Bedrock chat call completed")
        // key/value pairs are rendered in plain-text logs, not only in the json-logs profile
        .contains("httpStatus=\"200\"")
        .contains("totalTokens=");
    assertNoSecrets(output);
  }

  @Test
  void identityProviderRejection(CapturedOutput output) {
    idp.on(TOKEN_PATH, Response.json(401, "{\"error\":\"invalid_client\"}"));

    runAgent();

    assertThat(output.getAll())
        .contains("Organization authentication token request failed")
        .contains("Organization authentication token refresh failed");
    assertThat(gateway.requests()).isEmpty();
    assertNoSecrets(output);
  }

  @Test
  void gatewayServerErrorAndPersistent401(CapturedOutput output) {
    gateway.on(CONVERSE, (req, n) -> Response.json(n == 1 ? 503 : 401, "{\"message\":\"x\"}"));

    runAgent();

    assertThat(output.getAll())
        .contains("Organization Bedrock gateway returned an error status")
        .contains("Bedrock gateway authentication failed")
        .contains("Bedrock chat call failed");
    assertNoSecrets(output);
  }

  @Test
  void rejectedEndpointIsLogged(CapturedOutput output) {
    runAgent("https://bedrock-runtime.eu-central-1.amazonaws.com");

    assertThat(output.getAll())
        .contains("Rejecting Bedrock endpoint that is not an approved organization gateway")
        .contains("Endpoint rejected by organization gateway allow list")
        .contains("host differs");
    assertThat(gateway.requests()).isEmpty();
    assertNoSecrets(output);
  }
}
