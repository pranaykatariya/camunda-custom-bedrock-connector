package com.barclays.groupcontrol.co.camunda.connectors.camunda;

import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModel;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration;
import io.camunda.connector.agenticai.aiagent.model.request.provider.ProviderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Camunda integration point (Spring bean override).</b>
 *
 * <p>Replaces Camunda's {@code ChatModelFactory} bean, which {@code
 * AgenticAiLangchain4JFrameworkConfiguration#langchain4JChatModelFactory} declares with
 * {@code @ConditionalOnMissingBean}. It is a thin router:
 *
 * <ul>
 *   <li><b>Every Bedrock configuration</b> goes to {@link BarclaysBedrockChatModelBuilder} and
 *       authenticates with Barclays credentials. There is no fallback to Camunda's built-in
 *       Bedrock authentication.
 *   <li><b>Every other provider</b> (Anthropic, Azure OpenAI, Vertex AI, OpenAI, OpenAI-compatible)
 *       goes to an unmodified Camunda {@code ChatModelFactoryImpl}, built exactly like Camunda's
 *       default bean, with no Barclays authentication.
 * </ul>
 */
public class BarclaysGatewayChatModelFactory implements ChatModelFactory {

  private static final Logger LOG = LoggerFactory.getLogger(BarclaysGatewayChatModelFactory.class);

  private final ChatModelFactory standardFactory;
  private final BarclaysBedrockChatModelBuilder bedrockBuilder;

  public BarclaysGatewayChatModelFactory(
      ChatModelFactory standardFactory, BarclaysBedrockChatModelBuilder bedrockBuilder) {
    this.standardFactory = standardFactory;
    this.bedrockBuilder = bedrockBuilder;
    LOG.info("Camunda ChatModelFactory replaced by Barclays Bedrock gateway router");
  }

  @Override
  public CloseableChatModel createChatModel(ProviderConfiguration providerConfiguration) {
    if (providerConfiguration instanceof BedrockProviderConfiguration bedrock) {
      LOG.debug("Bedrock provider; routing to Barclays gateway chat model builder");
      return bedrockBuilder.create(bedrock);
    }
    // INFO on purpose: these calls do NOT carry Barclays credentials, which is worth seeing.
    LOG.atInfo()
        .addKeyValue("provider", providerConfiguration.getClass().getSimpleName())
        .log("Non-Bedrock provider; using standard Camunda chat model factory without Barclays authentication");
    return standardFactory.createChatModel(providerConfiguration);
  }
}
