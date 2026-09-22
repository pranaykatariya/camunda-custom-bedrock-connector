package com.anthrobyte.camunda.aiagent.support;

import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactoryImpl;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelHttpProxySupport;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication.AwsDefaultCredentialsChainAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockConnection;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockModel;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockModel.BedrockModelParameters;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiCompatibleProviderConfiguration;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiCompatibleProviderConfiguration.OpenAiCompatibleAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiCompatibleProviderConfiguration.OpenAiCompatibleConnection;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiCompatibleProviderConfiguration.OpenAiCompatibleModel;
import io.camunda.connector.agenticai.aiagent.model.request.provider.OpenAiCompatibleProviderConfiguration.OpenAiCompatibleModel.OpenAiCompatibleModelParameters;
import io.camunda.connector.agenticai.aiagent.model.request.provider.shared.TimeoutConfiguration;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.AiAgentProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ChatModelProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ChatModelProperties.ApiProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.HttpProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.HttpProperties.ProxySupportProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ToolsProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ToolsProperties.ProcessDefinitionProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ToolsProperties.ProcessDefinitionProperties.CacheProperties;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsConfigurationProperties.ToolsProperties.ProcessDefinitionProperties.RetriesProperties;
import io.camunda.connector.agenticai.common.AgenticAiHttpProxySupport;
import io.camunda.connector.http.client.proxy.ProxyConfiguration;
import java.time.Duration;
import java.util.Map;

/** Builds the Camunda objects the tests need, exactly as Camunda's own configuration does. */
public final class CamundaFixtures {

  private CamundaFixtures() {}

  /** Default model call timeout of {@link #agenticAiProperties()}. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

  public static final String BEDROCK_MODEL = "anthropic.claude-3-5-sonnet-20240620-v1:0";
  public static final String BEDROCK_REGION = "eu-central-1";

  public static AgenticAiConnectorsConfigurationProperties agenticAiProperties() {
    return agenticAiProperties(DEFAULT_TIMEOUT);
  }

  public static AgenticAiConnectorsConfigurationProperties agenticAiProperties(Duration defaultTimeout) {
    return new AgenticAiConnectorsConfigurationProperties(
        new ToolsProperties(
            new ProcessDefinitionProperties(
                new RetriesProperties(4, Duration.ofMillis(500)),
                new CacheProperties(true, 100L, Duration.ofMinutes(10)))),
        new AiAgentProperties(new ChatModelProperties(new ApiProperties(defaultTimeout))),
        new HttpProperties(new ProxySupportProperties(false)));
  }

  public static AgenticAiHttpProxySupport agenticAiHttpProxySupport() {
    return new AgenticAiHttpProxySupport(ProxyConfiguration.NONE);
  }

  /** Same construction as AgenticAiLangchain4JFrameworkConfiguration#langchain4JChatModelHttpProxySupport. */
  public static ChatModelHttpProxySupport chatModelHttpProxySupport() {
    final var support = agenticAiHttpProxySupport();
    return new ChatModelHttpProxySupport(
        support.getProxyConfiguration(), support.getJdkHttpClientProxyConfigurator());
  }

  /** Camunda's default ChatModelFactory, exactly as its @ConditionalOnMissingBean bean creates it. */
  public static ChatModelFactoryImpl standardChatModelFactory() {
    return new ChatModelFactoryImpl(agenticAiProperties(), chatModelHttpProxySupport());
  }

  public static OpenAiCompatibleProviderConfiguration openAiCompatible(
      String endpoint, String apiKey, Map<String, String> headers, Map<String, String> query) {
    return new OpenAiCompatibleProviderConfiguration(
        new OpenAiCompatibleConnection(
            endpoint,
            apiKey == null ? null : new OpenAiCompatibleAuthentication(apiKey),
            headers,
            query,
            null,
            new OpenAiCompatibleModel(
                "gpt-4o",
                new OpenAiCompatibleModelParameters(
                    256, 0.2, 0.9, Map.of("reasoning_effort", "low")))));
  }

  /** A Bedrock configuration as the AI Agent element produces it, with the default model. */
  public static BedrockProviderConfiguration bedrock(
      String endpoint,
      AwsAuthentication authentication,
      Duration timeout,
      BedrockModelParameters parameters) {
    return new BedrockProviderConfiguration(
        new BedrockConnection(
            BEDROCK_REGION,
            endpoint,
            authentication,
            timeout == null ? null : new TimeoutConfiguration(timeout),
            new BedrockModel(BEDROCK_MODEL, parameters)));
  }

  public static BedrockProviderConfiguration bedrock(String endpoint) {
    return bedrock(
        endpoint,
        new AwsDefaultCredentialsChainAuthentication(),
        null,
        new BedrockModelParameters(256, 0.2, 0.9));
  }
}
