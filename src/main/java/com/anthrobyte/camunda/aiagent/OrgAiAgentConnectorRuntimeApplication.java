package com.anthrobyte.camunda.aiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Camunda connector runtime that hosts the standard AI Agent connector (Task and Sub-process) with
 * organization authentication for the AWS Bedrock provider.
 *
 * <p>Everything is contributed via auto-configuration: Camunda's {@code
 * spring-boot-starter-camunda-connectors} and {@code connector-agentic-ai}, plus this project's
 * {@code OrganizationAuthAutoConfiguration}.
 */
@SpringBootApplication
public class OrgAiAgentConnectorRuntimeApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrgAiAgentConnectorRuntimeApplication.class, args);
  }
}
