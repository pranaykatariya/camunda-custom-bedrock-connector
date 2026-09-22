package com.anthrobyte.camunda.aiagent;

import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.contextRunner;
import static com.anthrobyte.camunda.aiagent.support.AgenticAiTestInfrastructure.outboundContext;
import static org.assertj.core.api.Assertions.assertThat;

import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import com.anthrobyte.camunda.aiagent.support.OpenAiResponses;
import com.anthrobyte.camunda.aiagent.transport.GatewayEndpointMatcher;
import io.camunda.connector.agenticai.aiagent.AiAgentFunction;
import io.camunda.connector.agenticai.aiagent.agent.JobWorkerAgentRequestHandler;
import io.camunda.connector.agenticai.aiagent.agent.OutboundConnectorAgentRequestHandler;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactoryImpl;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.Langchain4JAiFrameworkAdapter;
import io.camunda.connector.agenticai.aiagent.model.AgentResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * With {@code organization.ai-gateway.auth.enabled=false} the runtime <b>is</b> the standard
 * Camunda AI Agent connector: Camunda's own beans, Camunda's own authentication (API key from the
 * element), nothing from this project in the request path. With it enabled, every provider other
 * than Bedrock (here: OpenAI-compatible) still behaves exactly like the standard connector.
 */
class StandardBehaviourRegressionIT {

  private static final String CHAT_PATH = "/v1/chat/completions";

  private final FakeHttpServer llm =
      new FakeHttpServer().on(CHAT_PATH, Response.json(200, OpenAiResponses.text("Standard answer")));

  @AfterEach
  void tearDown() {
    llm.close();
  }

  private Map<String, Object> inputs(String apiKey) {
    final Map<String, Object> openaiCompatible = new HashMap<>();
    openaiCompatible.put("endpoint", llm.baseUrl() + "/v1");
    openaiCompatible.put("authentication", Map.of("apiKey", apiKey));
    openaiCompatible.put("model", Map.of("model", "gpt-4o"));
    final Map<String, Object> data = new HashMap<>();
    data.put("systemPrompt", Map.of("prompt", "You are helpful."));
    data.put("userPrompt", Map.of("prompt", "Hi"));
    data.put("memory", Map.of("storage", Map.of("type", "in-process"), "contextWindowSize", 10));
    data.put("limits", Map.of("maxModelCalls", 5));
    data.put("response", Map.of("format", Map.of("type", "text"), "includeAssistantMessage", true));
    return Map.of(
        "provider", Map.of("type", "openaiCompatible", "openaiCompatible", openaiCompatible),
        "data", data);
  }

  @Test
  void disabledByDefault_camundaBeansAreUsedUnchanged() {
    contextRunner()
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(ChatModelFactory.class))
                  .isExactlyInstanceOf(ChatModelFactoryImpl.class);
              assertThat(ctx).doesNotHaveBean(OrganizationGatewayChatModelFactory.class);
              assertThat(ctx).doesNotHaveBean(OrganizationAuthenticationProvider.class);
              assertThat(ctx).doesNotHaveBean(GatewayEndpointMatcher.class);
            });
  }

  @Test
  void explicitlyDisabled_aiAgentUsesElementApiKeyExactlyLikeStandardConnector() {
    contextRunner()
        .withPropertyValues(
            "organization.ai-gateway.auth.enabled=false",
            // even a complete (would-be valid) configuration must stay inert
            "organization.ai-gateway.auth.allowed-endpoints=" + llm.baseUrl() + "/v1",
            "organization.ai-gateway.auth.mode=STATIC_HEADERS",
            "organization.ai-gateway.auth.static-headers[0].name=X-Org",
            "organization.ai-gateway.auth.static-headers[0].value=must-not-be-sent")
        .run(
            ctx -> {
              assertThat(ctx.getBean(ChatModelFactory.class))
                  .isExactlyInstanceOf(ChatModelFactoryImpl.class);

              final AgentResponse response =
                  (AgentResponse)
                      ctx.getBean(AiAgentFunction.class)
                          .execute(outboundContext(inputs("element-api-key")));

              assertThat(response.responseText()).isEqualTo("Standard answer");
              final var request = llm.requests(CHAT_PATH).getFirst();
              assertThat(request.header("Authorization")).isEqualTo("Bearer element-api-key");
              assertThat(request.header("X-Org")).isNull();
            });
  }

  private static final String[] ENABLED = {
    "organization.ai-gateway.auth.enabled=true",
    "organization.ai-gateway.auth.allow-insecure-http=true",
    "organization.ai-gateway.auth.mode=STATIC_HEADERS",
    "organization.ai-gateway.auth.static-headers[0].name=x-bam-token",
    "organization.ai-gateway.auth.static-headers[0].value=must-not-be-sent"
  };

  private String[] enabledWithGatewayOnTheLlmHost() {
    // Even the OpenAI-compatible endpoint's own host is on the Bedrock allow-list: still no org auth.
    final String[] props = java.util.Arrays.copyOf(ENABLED, ENABLED.length + 1);
    props[ENABLED.length] = "organization.ai-gateway.auth.allowed-endpoints=" + llm.baseUrl();
    return props;
  }

  @Test
  void taskAndSubProcessShareTheSameFrameworkAdapterAndFactory() {
    // Both AI Agent variants (Task = outbound connector, Sub-process = job worker) reach the model
    // through the single Langchain4JAiFrameworkAdapter bean, hence through the one ChatModelFactory
    // bean that this project overrides.
    contextRunner()
        .withPropertyValues(enabledWithGatewayOnTheLlmHost())
        .run(
            ctx -> {
              final var adapter = ctx.getBean(Langchain4JAiFrameworkAdapter.class);
              assertThat(
                      ReflectionTestUtils.getField(
                          ctx.getBean(OutboundConnectorAgentRequestHandler.class), "framework"))
                  .isSameAs(adapter);
              assertThat(
                      ReflectionTestUtils.getField(
                          ctx.getBean(JobWorkerAgentRequestHandler.class), "framework"))
                  .isSameAs(adapter);
              assertThat(ReflectionTestUtils.getField(adapter, "chatModelFactory"))
                  .isInstanceOf(OrganizationGatewayChatModelFactory.class);
            });
  }

  @Test
  void enabled_openAiCompatibleStillUsesElementApiKeyAndNoOrganizationHeaders() {
    contextRunner()
        .withPropertyValues(enabledWithGatewayOnTheLlmHost())
        .run(
            ctx -> {
              final AgentResponse response =
                  (AgentResponse)
                      ctx.getBean(AiAgentFunction.class)
                          .execute(outboundContext(inputs("element-api-key")));

              assertThat(response.responseText()).isEqualTo("Standard answer");
              final var request = llm.requests(CHAT_PATH).getFirst();
              assertThat(request.header("Authorization")).isEqualTo("Bearer element-api-key");
              assertThat(request.header("x-bam-token")).isNull();
            });
  }

  @Test
  void openAiCompatibleIsIdenticalWithAndWithoutOrganizationAuthentication() {
    final List<AgentResponse> responses = new java.util.ArrayList<>();
    contextRunner()
        .run(
            ctx ->
                responses.add(
                    (AgentResponse)
                        ctx.getBean(AiAgentFunction.class).execute(outboundContext(inputs("k")))));
    contextRunner()
        .withPropertyValues(enabledWithGatewayOnTheLlmHost())
        .run(
            ctx ->
                responses.add(
                    (AgentResponse)
                        ctx.getBean(AiAgentFunction.class).execute(outboundContext(inputs("k")))));

    final var standard = responses.get(0);
    final var customized = responses.get(1);
    assertThat(customized.responseText()).isEqualTo(standard.responseText());
    assertThat(customized.responseMessage().content()).isEqualTo(standard.responseMessage().content());
    assertThat(customized.toolCalls()).isEqualTo(standard.toolCalls());
    assertThat(customized.context().metrics()).isEqualTo(standard.context().metrics());
    assertThat(customized.context().state()).isEqualTo(standard.context().state());

    final var requests = llm.requests(CHAT_PATH);
    assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
    assertThat(requests.get(1).headers()).isEqualTo(requests.get(0).headers());
  }
}
