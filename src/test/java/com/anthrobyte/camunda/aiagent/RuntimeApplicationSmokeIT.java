package com.anthrobyte.camunda.aiagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthrobyte.camunda.aiagent.auth.CachingTokenAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.camunda.OrganizationGatewayChatModelFactory;
import io.camunda.client.annotation.value.JobWorkerValue;
import io.camunda.client.annotation.value.SourceAware.FromAnnotation;
import io.camunda.connector.agenticai.aiagent.AiAgentJobWorker;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.jobworker.AiAgentJobWorkerValueCustomizer;
import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration;
import io.camunda.connector.runtime.core.outbound.OutboundConnectorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Boots the real application: {@code application.yml}, Camunda's
 * spring-boot-starter-camunda-connectors, connector-agentic-ai and this project's
 * auto-configuration, ordered by Spring Boot itself.
 *
 * <p>The Camunda cluster is not reachable (not needed to verify wiring). Secrets are supplied the
 * way they are in production: through the ORG_* environment placeholders of application.yml.
 */
@SpringBootTest(
    properties = {
      "camunda.client.grpc-address=http://127.0.0.1:1",
      "camunda.client.rest-address=http://127.0.0.1:1",
      "camunda.connector.polling.enabled=false",
      "camunda.connector.webhook.enabled=false",
      "ORG_AI_GATEWAY_HOST_HEADER=bedrock-gateway.internal.example"
    })
class RuntimeApplicationSmokeIT {

  @Autowired ApplicationContext context;

  @Test
  void camundaChatModelFactoryIsReplacedInTheRealApplication() {
    assertThat(context.getBeansOfType(ChatModelFactory.class)).hasSize(1);
    assertThat(context.getBean(ChatModelFactory.class))
        .isInstanceOf(OrganizationGatewayChatModelFactory.class);
    // application.yml default mode: placeholder JWT in x-bam-token, plus Accept and Host
    final var provider = context.getBean(OrganizationAuthenticationProvider.class);
    assertThat(provider).isInstanceOf(CachingTokenAuthenticationProvider.class);
    assertThat(provider.getCredentials().headers())
        .containsEntry("Accept", "application/json")
        .containsEntry("Host", "bedrock-gateway.internal.example")
        .containsKey("x-bam-token");
  }

  @Test
  void aiAgentTaskUsesCustomJobType() {
    final var factory = context.getBean(OutboundConnectorFactory.class);

    assertThat(factory.getConfigurations())
        .filteredOn(c -> "AI Agent".equals(c.name()))
        .singleElement()
        .extracting(OutboundConnectorConfiguration::type)
        .isEqualTo("org.ai-gateway:aiagent:1");
  }

  @Test
  void aiAgentSubProcessUsesCustomJobType() {
    final var value = new JobWorkerValue();
    value.setName(new FromAnnotation<>(AiAgentJobWorker.JOB_WORKER_NAME));
    value.setType(new FromAnnotation<>(AiAgentJobWorker.JOB_WORKER_TYPE));

    context.getBean(AiAgentJobWorkerValueCustomizer.class).customize(value);

    assertThat(value.getType().value()).isEqualTo("org.ai-gateway:aiagent-job-worker:1");
  }

  @Test
  void onlyTheAiAgentConnectorsAreRegisteredFromTheAgenticModule() {
    assertThat(context.getBean(OutboundConnectorFactory.class).getConfigurations())
        .extracting(OutboundConnectorConfiguration::type)
        .noneMatch(type -> type.startsWith("io.camunda.agenticai:"));
  }
}
