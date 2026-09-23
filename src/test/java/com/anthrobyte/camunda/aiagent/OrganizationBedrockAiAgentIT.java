package com.anthrobyte.camunda.aiagent;

import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.TOOLS_CONTAINER_ID;
import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.contextRunner;
import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.outboundContext;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.BEDROCK_MODEL;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.BEDROCK_REGION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
import com.anthrobyte.camunda.aiagent.support.BedrockResponses;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import com.anthrobyte.camunda.aiagent.support.TokenResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.agenticai.aiagent.AiAgentFunction;
import io.camunda.connector.agenticai.aiagent.agent.AgentErrorCodes;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.Langchain4JAiFrameworkAdapter;
import io.camunda.connector.agenticai.aiagent.model.AgentResponse;
import io.camunda.connector.agenticai.aiagent.model.AgentState;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * End-to-end: the <b>unmodified</b> Camunda AI Agent Task ({@link AiAgentFunction}, with its
 * agent initializer, tool resolution, memory, limits and response handling) running on this
 * runtime with the AWS Bedrock provider, calling a fake organization Bedrock gateway that requires
 * an {@code x-bam-token} obtained with OAuth2 client credentials.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrganizationBedrockAiAgentIT {

  private static final String TOKEN_PATH = "/oauth2/token";
  private static final String CONVERSE = "/bedrock" + BedrockResponses.conversePath(BEDROCK_MODEL);
  private static final String CLIENT_SECRET = "it-client-secret-value";

  private final FakeHttpServer idp = new FakeHttpServer();
  private final FakeHttpServer gateway = new FakeHttpServer();
  // The mapper the connector runtime uses to turn results into process variables.
  private final ObjectMapper json = ConnectorsObjectMapperSupplier.getCopy();

  @AfterEach
  void tearDown() {
    idp.close();
    gateway.close();
  }

  private ApplicationContextRunner runtime() {
    idp.on(TOKEN_PATH, (req, n) -> Response.json(200, TokenResponses.token("org-jwt-" + n, 3600)));
    return contextRunner()
        .withPropertyValues(
            "organization.ai-gateway.auth.enabled=true",
            "organization.ai-gateway.auth.allow-insecure-http=true",
            "organization.ai-gateway.auth.mode=OAUTH2_CLIENT_CREDENTIALS",
            "organization.ai-gateway.auth.oauth2.token-uri=" + idp.baseUrl() + TOKEN_PATH,
            "organization.ai-gateway.auth.oauth2.client-id=camunda-ai-agent",
            "organization.ai-gateway.auth.oauth2.client-secret=" + CLIENT_SECRET,
            "organization.ai-gateway.auth.static-headers[0].name=Accept",
            "organization.ai-gateway.auth.static-headers[0].value=application/json",
            "organization.ai-gateway.auth.static-headers[1].name=Host",
            "organization.ai-gateway.auth.static-headers[1].value=bedrock-gateway.internal.example");
  }

  /** AI Agent Task inputs, as the organization element template maps them. No AWS keys anywhere. */
  private Map<String, Object> agentTaskInputs(
      String endpoint, Map<String, Object> agentContext, List<?> toolResults) {
    final Map<String, Object> bedrock = new HashMap<>();
    bedrock.put("region", BEDROCK_REGION);
    bedrock.put("endpoint", endpoint);
    bedrock.put("authentication", Map.of("type", "defaultCredentialsChain"));
    bedrock.put(
        "model",
        Map.of("model", BEDROCK_MODEL, "parameters", Map.of("maxTokens", 512, "temperature", 0.1)));

    final Map<String, Object> data = new HashMap<>();
    data.put("context", agentContext);
    data.put("systemPrompt", Map.of("prompt", "You are a helpful claims assistant."));
    data.put("userPrompt", Map.of("prompt", "What is the weather in Berlin?"));
    data.put("tools", Map.of("containerElementId", TOOLS_CONTAINER_ID, "toolCallResults", toolResults));
    data.put("memory", Map.of("storage", Map.of("type", "in-process"), "contextWindowSize", 20));
    data.put("limits", Map.of("maxModelCalls", 10));
    data.put("response", Map.of("format", Map.of("type", "text"), "includeAssistantMessage", true));

    return Map.of("provider", Map.of("type", "bedrock", "bedrock", bedrock), "data", data);
  }

  private Map<String, Object> agentTaskInputs(Map<String, Object> agentContext, List<?> toolResults) {
    return agentTaskInputs(gateway.baseUrl() + "/bedrock", agentContext, toolResults);
  }

  @Test
  void camundaChatModelFactoryBeanIsReplacedAndUsedByTheUnmodifiedAdapter() {
    runtime()
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(ChatModelFactory.class);
              assertThat(ctx.getBean(ChatModelFactory.class))
                  .isInstanceOf(OrganizationGatewayChatModelFactory.class);
              // Camunda's own adapter (not ours) received our factory through normal injection.
              assertThat(ctx).hasSingleBean(Langchain4JAiFrameworkAdapter.class);
              assertThat(
                      ReflectionTestUtils.getField(
                          ctx.getBean(Langchain4JAiFrameworkAdapter.class), "chatModelFactory"))
                  .isSameAs(ctx.getBean(ChatModelFactory.class));
              assertThat(ctx).hasSingleBean(AiAgentFunction.class);
            });
  }

  @Test
  void aiAgentTaskToolCallingRoundTripThroughAuthenticatedBedrockGateway() {
    gateway.on(
        CONVERSE,
        (req, n) ->
            Response.json(
                200,
                n == 1
                    ? BedrockResponses.toolUse("call_1", "GetWeather", "{\"city\":\"Berlin\"}")
                    : BedrockResponses.text("It is sunny in Berlin.")));

    runtime()
        .run(
            ctx -> {
              final AiAgentFunction agent = ctx.getBean(AiAgentFunction.class);

              // --- turn 1: the model asks for a tool call
              final AgentResponse first =
                  (AgentResponse) agent.execute(outboundContext(agentTaskInputs(null, List.of())));

              assertThat(first.toolCalls()).hasSize(1);
              final JsonNode toolCall = json.valueToTree(first.toolCalls().getFirst());
              assertThat(toolCall.toString()).contains("GetWeather").contains("Berlin").contains("call_1");
              assertThat(first.context().state()).isEqualTo(AgentState.READY);

              // Outgoing request: standard Bedrock Converse payload incl. tool schema, org headers.
              final var request1 = gateway.requests(CONVERSE).get(0);
              final JsonNode body1 = json.readTree(request1.body());
              assertThat(body1.get("system").get(0).get("text").asText()).contains("claims assistant");
              assertThat(body1.get("messages").get(0).get("role").asText()).isEqualTo("user");
              assertThat(body1.get("inferenceConfig").get("maxTokens").asInt()).isEqualTo(512);
              assertThat(body1.get("toolConfig").get("tools").get(0).get("toolSpec").get("name").asText())
                  .isEqualTo("GetWeather");
              assertThat(request1.header("x-bam-token")).isEqualTo("org-jwt-1");
              assertThat(request1.header("Accept")).isEqualTo("application/json");
              assertThat(request1.header("Host")).isEqualTo("bedrock-gateway.internal.example");
              assertThat(request1.header("Authorization")).isNull();
              assertThat(request1.header("X-Amz-Date")).isNull();

              // --- turn 2: tool result goes back, the model answers
              final Map<String, Object> agentContext = json.convertValue(first.context(), Map.class);
              final List<Map<String, Object>> toolResults =
                  List.of(Map.of("id", "call_1", "name", "GetWeather", "content", Map.of("forecast", "sunny")));
              final AgentResponse second =
                  (AgentResponse) agent.execute(outboundContext(agentTaskInputs(agentContext, toolResults)));

              assertThat(second.responseText()).isEqualTo("It is sunny in Berlin.");
              assertThat(second.toolCalls()).isEmpty();
              assertThat(second.context().metrics().modelCalls()).isEqualTo(2);

              final JsonNode body2 = json.readTree(gateway.requests(CONVERSE).get(1).body());
              final JsonNode messages = body2.get("messages");
              assertThat(messages.get(messages.size() - 1).toString()).contains("toolResult").contains("call_1").contains("sunny");

              // One token served both turns (cached), obtained with client credentials.
              assertThat(idp.callCount(TOKEN_PATH)).isEqualTo(1);
              assertThat(gateway.requests(CONVERSE).get(1).header("x-bam-token")).isEqualTo("org-jwt-1");
            });
  }

  @Test
  void tokenRejectedByGatewayIsRefreshedTransparently() {
    gateway.on(
        CONVERSE,
        (req, n) ->
            "org-jwt-1".equals(req.header("x-bam-token"))
                ? Response.json(401, BedrockResponses.error("token expired"))
                : Response.json(200, BedrockResponses.text("Fine.")));

    runtime()
        .run(
            ctx -> {
              final AgentResponse response =
                  (AgentResponse)
                      ctx.getBean(AiAgentFunction.class).execute(outboundContext(agentTaskInputs(null, List.of())));

              assertThat(response.responseText()).isEqualTo("Fine.");
              assertThat(idp.callCount(TOKEN_PATH)).isEqualTo(2);
              assertThat(gateway.requests(CONVERSE))
                  .extracting(r -> r.header("x-bam-token"))
                  .containsExactly("org-jwt-1", "org-jwt-2");
            });
  }

  @Test
  void gatewayAuthenticationFailureSurfacesAsStandardModelCallErrorWithoutSecrets(CapturedOutput output) {
    gateway.on(CONVERSE, Response.json(401, BedrockResponses.error("nope")));

    runtime()
        .run(
            ctx ->
                assertThatThrownBy(
                        () ->
                            ctx.getBean(AiAgentFunction.class)
                                .execute(outboundContext(agentTaskInputs(null, List.of()))))
                    .isInstanceOf(ConnectorException.class)
                    .satisfies(
                        e -> {
                          final var ce = (ConnectorException) e;
                          assertThat(ce.getErrorCode()).isEqualTo(AgentErrorCodes.ERROR_CODE_FAILED_MODEL_CALL);
                          assertThat(ce.getMessage())
                              .startsWith("Model call failed: Organization Bedrock gateway authentication failed")
                              .doesNotContain("org-jwt");
                        }));

    // original + one refresh-retry; the non-retriable exception stops LangChain4j's retry loop
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(2);
    assertThat(output.getAll()).doesNotContain("org-jwt").doesNotContain(CLIENT_SECRET);
  }

  @Test
  void identityProviderOutageFailsClosedWithoutCallingTheGateway(CapturedOutput output) {
    runtime()
        .run(
            ctx -> {
              idp.on(TOKEN_PATH, Response.json(503, "{\"error\":\"temporarily_unavailable\"}"));

              assertThatThrownBy(
                      () ->
                          ctx.getBean(AiAgentFunction.class)
                              .execute(outboundContext(agentTaskInputs(null, List.of()))))
                  .isInstanceOf(ConnectorException.class)
                  .hasMessageContaining("Organization Bedrock gateway authentication failed")
                  .hasMessageContaining("temporarily unavailable")
                  .extracting(e -> ((ConnectorException) e).getErrorCode())
                  .isEqualTo(AgentErrorCodes.ERROR_CODE_FAILED_MODEL_CALL);

              assertThat(idp.callCount(TOKEN_PATH)).isEqualTo(1);
              assertThat(gateway.requests()).isEmpty();
            });
    assertThat(output.getAll()).doesNotContain(CLIENT_SECRET);
  }

  @Test
  void bedrockEndpointThatIsNotAUsableUrlFailsClosed() {
    runtime()
        .run(
            ctx -> {
              assertThatThrownBy(
                      () ->
                          ctx.getBean(AiAgentFunction.class)
                              .execute(
                                  outboundContext(
                                      agentTaskInputs("bedrock-gateway.example.com", null, List.of()))))
                  .hasMessageContaining("Organization Bedrock gateway authentication failed")
                  .hasMessageContaining("custom endpoint is not an absolute url with a host");

              assertThat(idp.requests()).isEmpty();
              assertThat(gateway.requests()).isEmpty();
            });
  }

  @Test
  void llmErrorsKeepStandardSemantics() {
    gateway.on(CONVERSE, Response.json(400, BedrockResponses.error("Input is too long for requested model.")));

    runtime()
        .run(
            ctx ->
                assertThatThrownBy(
                        () ->
                            ctx.getBean(AiAgentFunction.class)
                                .execute(outboundContext(agentTaskInputs(null, List.of()))))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("Input is too long")
                    .hasMessageNotContaining("Organization Bedrock gateway authentication failed"));
    // 400 is non-retriable in LangChain4j: exactly one call, no auth retry.
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(1);
  }
}
