package com.anthrobyte.camunda.aiagent.camunda;

import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.BEDROCK_MODEL;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.agenticAiHttpProxySupport;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.agenticAiProperties;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.bedrock;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.openAiCompatible;
import static com.anthrobyte.camunda.aiagent.support.CamundaFixtures.standardChatModelFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthrobyte.camunda.aiagent.auth.GatewayCredentials;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationException;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.support.BedrockResponses;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import com.anthrobyte.camunda.aiagent.support.OpenAiResponses;
import com.anthrobyte.camunda.aiagent.transport.GatewayEndpointMatcher;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModel;
import io.camunda.connector.agenticai.aiagent.model.request.provider.AnthropicProviderConfiguration;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication.AwsApiKeyAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiProviderConfiguration;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Routing of the {@code ChatModelFactory} bean: every Bedrock configuration is organization
 * authenticated (ALL_BEDROCK), every other provider is Camunda's standard behaviour (PASS_THROUGH).
 */
class OrganizationGatewayChatModelFactoryTest {

  private static final String CONVERSE = "/bedrock" + BedrockResponses.conversePath(BEDROCK_MODEL);
  private static final String OPENAI_CHAT = "/v1/chat/completions";

  private final FakeHttpServer server =
      new FakeHttpServer()
          .on(CONVERSE, Response.json(200, BedrockResponses.text("bedrock ok")))
          .on(OPENAI_CHAT, Response.json(200, OpenAiResponses.text("openai ok")));

  private final OrganizationAuthenticationProvider organizationAuth =
      () -> GatewayCredentials.of("x-bam-token", "org-token");

  @AfterEach
  void tearDown() {
    server.close();
  }

  private OrganizationGatewayChatModelFactory router(ChatModelFactory standard) {
    return new OrganizationGatewayChatModelFactory(
        standard,
        new OrganizationBedrockChatModelBuilder(
            agenticAiProperties(),
            agenticAiHttpProxySupport(),
            organizationAuth,
            new GatewayEndpointMatcher(List.of(URI.create(server.baseUrl() + "/bedrock"))),
            true,
            true));
  }

  private static String chat(CloseableChatModel model) {
    try (model) {
      return model.chat(ChatRequest.builder().messages(UserMessage.from("Hi")).build()).aiMessage().text();
    }
  }

  @Test
  void everyBedrockConfigurationUsesOrganizationAuthentication() {
    final ChatModelFactory standard = mock(ChatModelFactory.class);
    final var router = router(standard);

    assertThat(chat(router.createChatModel(bedrock(server.baseUrl() + "/bedrock")))).isEqualTo("bedrock ok");
    // even with the element set to Camunda's API-key mode: no fallback to built-in Bedrock auth
    assertThat(
            chat(
                router.createChatModel(
                    bedrock(server.baseUrl() + "/bedrock", new AwsApiKeyAuthentication("element-key"), null, null))))
        .isEqualTo("bedrock ok");

    assertThat(server.requests(CONVERSE))
        .allSatisfy(
            r -> {
              assertThat(r.header("x-bam-token")).isEqualTo("org-token");
              assertThat(r.header("Authorization")).isNull();
            });
    verifyNoInteractions(standard);
  }

  @Test
  void bedrockOutsideTheGatewayIsRejectedNotPassedThrough() {
    final ChatModelFactory standard = mock(ChatModelFactory.class);

    assertThatThrownBy(() -> router(standard).createChatModel(bedrock(null)))
        .isInstanceOf(OrganizationAuthenticationException.class);
    assertThatThrownBy(() -> router(standard).createChatModel(bedrock("https://bedrock-runtime.eu-central-1.amazonaws.com")))
        .isInstanceOf(OrganizationAuthenticationException.class);
    verifyNoInteractions(standard);
  }

  @Test
  void openAiCompatibleIsNoLongerOrganizationAuthenticated() {
    // Camunda's real factory: the element API key is used, organization headers are not sent,
    // even when the endpoint is on the organization gateway host.
    final var config = openAiCompatible(server.baseUrl() + "/v1", "element-api-key", Map.of(), Map.of());

    assertThat(chat(router(standardChatModelFactory()).createChatModel(config))).isEqualTo("openai ok");

    final var request = server.requests(OPENAI_CHAT).getFirst();
    assertThat(request.header("Authorization")).isEqualTo("Bearer element-api-key");
    assertThat(request.header("x-bam-token")).isNull();
    assertThat(server.requests(CONVERSE)).isEmpty();
  }

  @Test
  void otherProvidersAreDelegatedUntouched() {
    final ChatModelFactory standard = mock(ChatModelFactory.class);
    final var anthropic = mock(AnthropicProviderConfiguration.class);
    final var openai = mock(OpenAiProviderConfiguration.class);
    final var anthropicModel = mock(CloseableChatModel.class);
    final var openaiModel = mock(CloseableChatModel.class);
    when(standard.createChatModel(anthropic)).thenReturn(anthropicModel);
    when(standard.createChatModel(openai)).thenReturn(openaiModel);

    final var router = router(standard);

    assertThat(router.createChatModel(anthropic)).isSameAs(anthropicModel);
    assertThat(router.createChatModel(openai)).isSameAs(openaiModel);
    verify(standard).createChatModel(anthropic);
    verify(standard).createChatModel(openai);
  }
}
