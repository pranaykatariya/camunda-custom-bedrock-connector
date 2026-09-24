package com.barclays.groupcontrol.co.camunda.connectors;

import static com.barclays.groupcontrol.co.camunda.connectors.support.AgenticAiTestInfrastructure.contextRunner;
import static com.barclays.groupcontrol.co.camunda.connectors.support.AgenticAiTestInfrastructure.outboundContext;
import static org.assertj.core.api.Assertions.assertThat;

import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
import com.barclays.groupcontrol.co.camunda.connectors.camunda.BarclaysGatewayChatModelFactory;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.Response;
import com.barclays.groupcontrol.co.camunda.connectors.support.OpenAiResponses;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * With {@code barclays.ai-gateway.auth.enabled=false} the runtime <b>is</b> the standard
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
              assertThat(ctx).doesNotHaveBean(BarclaysGatewayChatModelFactory.class);
              assertThat(ctx).doesNotHaveBean(BamTokenCache.class);
            });
  }

  @Test
  void explicitlyDisabled_aiAgentUsesElementApiKeyExactlyLikeStandardConnector() {
    contextRunner()
        .withPropertyValues(
            "barclays.ai-gateway.auth.enabled=false",
            // even a complete (would-be valid) configuration must stay inert
            "barclays.ai-gateway.auth.static-headers[0].name=X-Barclays",
            "barclays.ai-gateway.auth.static-headers[0].value=must-not-be-sent")
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
              assertThat(request.header("X-Barclays")).isNull();
            });
  }

  private static ApplicationContextRunner enabled() {
    // Non-Bedrock providers never request a BAM token, so the endpoint is never called.
    return contextRunner()
        .withPropertyValues(
            "barclays.ai-gateway.auth.enabled=true",
            "barclays.ai-gateway.auth.bam.token-url=https://bam.invalid/api/token",
            "barclays.ai-gateway.auth.bam.username=bam-user",
            "barclays.ai-gateway.auth.bam.password=must-not-be-sent");
  }

  @Test
  void taskAndSubProcessShareTheSameFrameworkAdapterAndFactory() {
    // Both AI Agent variants (Task = outbound connector, Sub-process = job worker) reach the model
    // through the single Langchain4JAiFrameworkAdapter bean, hence through the one ChatModelFactory
    // bean that this project overrides.
    enabled()
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
                  .isInstanceOf(BarclaysGatewayChatModelFactory.class);
            });
  }

  @Test
  void enabled_openAiCompatibleStillUsesElementApiKeyAndNoBarclaysHeaders() {
    enabled()
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
  void openAiCompatibleIsIdenticalWithAndWithoutBarclaysAuthentication() {
    final List<AgentResponse> responses = new java.util.ArrayList<>();
    contextRunner()
        .run(
            ctx ->
                responses.add(
                    (AgentResponse)
                        ctx.getBean(AiAgentFunction.class).execute(outboundContext(inputs("k")))));
    enabled()
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
