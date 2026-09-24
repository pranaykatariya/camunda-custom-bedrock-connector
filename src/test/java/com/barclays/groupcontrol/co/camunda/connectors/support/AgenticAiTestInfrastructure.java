package com.barclays.groupcontrol.co.camunda.connectors.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.barclays.groupcontrol.co.camunda.connectors.config.BarclaysAuthAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.jobhandling.CommandExceptionHandlingStrategy;
import io.camunda.client.metrics.MetricsRecorder;
import io.camunda.connector.agenticai.adhoctoolsschema.model.AdHocToolElement;
import io.camunda.connector.agenticai.adhoctoolsschema.model.AdHocToolElementParameter;
import io.camunda.connector.agenticai.adhoctoolsschema.processdefinition.ProcessDefinitionAdHocToolElementsResolver;
import io.camunda.connector.agenticai.autoconfigure.AgenticAiConnectorsAutoConfiguration;
import io.camunda.connector.api.document.DocumentFactory;
import io.camunda.connector.api.outbound.JobContext;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;
import io.camunda.connector.runtime.annotation.ConnectorsObjectMapper;
import io.camunda.connector.runtime.core.document.store.CamundaDocumentStore;
import io.camunda.connector.runtime.core.secret.SecretProviderAggregator;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * Boots Camunda's <b>real</b> {@code AgenticAiConnectorsAutoConfiguration} together with this
 * project's auto-configuration, in the same order Spring Boot uses in the runtime. Only the
 * Camunda cluster infrastructure (client, document store, metrics) is mocked, because it is not
 * involved in model calls.
 */
public final class AgenticAiTestInfrastructure {

  public static final String TOOLS_CONTAINER_ID = "agent_tools";
  public static final long PROCESS_DEFINITION_KEY = 2251799813685249L;

  private AgenticAiTestInfrastructure() {}

  public static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                BarclaysAuthAutoConfiguration.class, AgenticAiConnectorsAutoConfiguration.class))
        .withUserConfiguration(InfrastructureMocks.class)
        .withPropertyValues(
            // mirror src/main/resources/application.yml
            "camunda.connector.agenticai.http.proxy-support.enabled=false",
            "camunda.connector.agenticai.ad-hoc-tools-schema-resolver.enabled=false",
            "camunda.connector.agenticai.mcp.remote-client.enabled=false",
            "camunda.connector.agenticai.a2a.client.outbound.enabled=false",
            "camunda.connector.agenticai.a2a.client.polling.enabled=false",
            "camunda.connector.agenticai.a2a.client.webhook.enabled=false",
            "camunda.connector.agenticai.a2a.client.agentic.tool.enabled=false");
  }

  static class InfrastructureMocks {

    @Bean
    CamundaClient camundaClient() {
      return mock(CamundaClient.class);
    }

    @Bean
    DocumentFactory documentFactory() {
      return mock(DocumentFactory.class);
    }

    @Bean
    CamundaDocumentStore camundaDocumentStore() {
      return mock(CamundaDocumentStore.class);
    }

    @Bean
    CommandExceptionHandlingStrategy commandExceptionHandlingStrategy() {
      return mock(CommandExceptionHandlingStrategy.class);
    }

    @Bean
    MetricsRecorder metricsRecorder() {
      return mock(MetricsRecorder.class);
    }

    @Bean
    SecretProviderAggregator secretProviderAggregator() {
      return new SecretProviderAggregator(List.of());
    }

    @Bean
    @ConnectorsObjectMapper
    ObjectMapper connectorsObjectMapper() {
      return ConnectorsObjectMapperSupplier.getCopy();
    }

    /**
     * Replaces the resolver that would read the BPMN from the cluster. Declares one tool inside the
     * ad-hoc sub-process, as a modeler would.
     */
    @Bean
    ProcessDefinitionAdHocToolElementsResolver processDefinitionAdHocToolElementsResolver() {
      final var resolver = mock(ProcessDefinitionAdHocToolElementsResolver.class);
      when(resolver.resolveToolElements(anyLong(), eq(TOOLS_CONTAINER_ID)))
          .thenReturn(
              List.of(
                  AdHocToolElement.builder()
                      .elementId("GetWeather")
                      .elementName("Get weather")
                      .documentation("Returns the current weather for a city")
                      .properties(Map.of())
                      .parameters(
                          List.of(
                              new AdHocToolElementParameter(
                                  "toolCall.city", "The city name", "string")))
                      .build()));
      return resolver;
    }
  }

  /** A connector context as the runtime creates it after FEEL evaluation of the element inputs. */
  public static OutboundConnectorContext outboundContext(Map<String, Object> inputs) {
    final ObjectMapper mapper = ConnectorsObjectMapperSupplier.getCopy();
    final var context = mock(OutboundConnectorContext.class);
    when(context.bindVariables(any()))
        .thenAnswer(inv -> mapper.convertValue(inputs, (Class<?>) inv.getArgument(0)));
    final var jobContext = mock(JobContext.class);
    when(jobContext.getBpmnProcessId()).thenReturn("support-agent");
    when(jobContext.getProcessDefinitionKey()).thenReturn(PROCESS_DEFINITION_KEY);
    when(jobContext.getProcessInstanceKey()).thenReturn(2251799813685300L);
    when(jobContext.getElementId()).thenReturn("AI_Agent");
    when(jobContext.getElementInstanceKey()).thenReturn(2251799813685310L);
    when(jobContext.getTenantId()).thenReturn("<default>");
    when(context.getJobContext()).thenReturn(jobContext);
    return context;
  }
}
