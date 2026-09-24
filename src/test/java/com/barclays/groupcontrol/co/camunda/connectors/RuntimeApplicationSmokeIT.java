package com.barclays.groupcontrol.co.camunda.connectors;

import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
import com.barclays.groupcontrol.co.camunda.connectors.camunda.BarclaysGatewayChatModelFactory;
import com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer;
import io.camunda.client.annotation.value.JobWorkerValue;
import io.camunda.client.annotation.value.SourceAware.FromAnnotation;
import io.camunda.connector.agenticai.aiagent.AiAgentJobWorker;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.ChatModelFactory;
import io.camunda.connector.agenticai.aiagent.jobworker.AiAgentJobWorkerValueCustomizer;
import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration;
import io.camunda.connector.runtime.core.outbound.OutboundConnectorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots a Spring Boot application the way the Camunda connector runtime does, with this jar on its
 * classpath: {@code application.yml}, Camunda's
 * spring-boot-starter-camunda-connectors, connector-agentic-ai and this project's
 * auto-configuration, ordered by Spring Boot itself.
 *
 * <p>The Camunda cluster is not reachable (not needed to verify wiring). Secrets are supplied the
 * way they are in production: through the BARCLAYS_* environment placeholders of application.yml.
 */
@SpringBootTest(
    properties = {
      "camunda.client.grpc-address=http://127.0.0.1:1",
      "camunda.client.rest-address=http://127.0.0.1:1",
      "camunda.connector.polling.enabled=false",
      "camunda.connector.webhook.enabled=false",
      "BARCLAYS_AI_GATEWAY_HOST_HEADER=bedrock-gateway.internal.example"
    })
class RuntimeApplicationSmokeIT {

  /** Stands in for the Camunda connector runtime's main class. */
  @SpringBootApplication
  static class ConnectorRuntime {}

  private static final FakeHttpServer BAM = new FakeHttpServer().on(TOKEN_PATH, BamResponses.issuing());

  /** The BAM secrets, supplied through the same environment placeholders as in production. */
  @DynamicPropertySource
  static void bamSecrets(DynamicPropertyRegistry registry) {
    registry.add("BARCLAYS_AI_BAM_TOKEN_URL", () -> BAM.baseUrl() + TOKEN_PATH);
    registry.add("BARCLAYS_AI_BAM_USERNAME", () -> BamResponses.USERNAME);
    registry.add("BARCLAYS_AI_BAM_PASSWORD", () -> BamResponses.PASSWORD);
    // the fake endpoint is plain http
    registry.add("barclays.ai-gateway.auth.allow-insecure-http", () -> "true");
  }

  @AfterAll
  static void stopBam() {
    BAM.close();
  }

  @Autowired ApplicationContext context;

  @Test
  void camundaChatModelFactoryIsReplacedInTheRealApplication() {
    assertThat(context.getBeansOfType(ChatModelFactory.class)).hasSize(1);
    assertThat(context.getBean(ChatModelFactory.class))
        .isInstanceOf(BarclaysGatewayChatModelFactory.class);
    // application.yml defaults: the BAM token in x-bam-token, plus Accept and Host
    final var cache = context.getBean(BamTokenCache.class);
    assertThat(cache.getCredentials().headers())
        .containsEntry("Accept", "application/json")
        .containsEntry("Host", "bedrock-gateway.internal.example")
        .containsEntry("x-bam-token", BamResponses.jwt(1));
    assertThat(BAM.requests(TOKEN_PATH).getFirst().header("Authorization"))
        .isEqualTo(BamResponses.basicAuthorization());
  }

  @Test
  void aiAgentTaskUsesCustomJobType() {
    final var factory = context.getBean(OutboundConnectorFactory.class);

    assertThat(factory.getConfigurations())
        .filteredOn(c -> "AI Agent".equals(c.name()))
        .singleElement()
        .extracting(OutboundConnectorConfiguration::type)
        .isEqualTo("barclays.ai-gateway:aiagent:1");
  }

  @Test
  void aiAgentSubProcessUsesCustomJobType() {
    final var value = new JobWorkerValue();
    value.setName(new FromAnnotation<>(AiAgentJobWorker.JOB_WORKER_NAME));
    value.setType(new FromAnnotation<>(AiAgentJobWorker.JOB_WORKER_TYPE));

    context.getBean(AiAgentJobWorkerValueCustomizer.class).customize(value);

    assertThat(value.getType().value()).isEqualTo("barclays.ai-gateway:aiagent-job-worker:1");
  }

  @Test
  void onlyTheAiAgentConnectorsAreRegisteredFromTheAgenticModule() {
    assertThat(context.getBean(OutboundConnectorFactory.class).getConfigurations())
        .extracting(OutboundConnectorConfiguration::type)
        .noneMatch(type -> type.startsWith("io.camunda.agenticai:"));
  }
}
