package com.anthrobyte.camunda.aiagent;

import com.anthrobyte.camunda.aiagent.config.OrganizationAuthProperties;
import io.camunda.connector.agenticai.aiagent.AiAgentFunction;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs one summary line when the runtime is ready: versions, the AI Agent job types it polls and
 * whether organization authentication is on. The job types matter most: if they are the standard
 * ones, this runtime competes with the default connector runtime for every AI Agent job.
 *
 * <p>Only configuration names and non-secret values are logged.
 */
@Component
class RuntimeStartupReport {

  private static final Logger LOG = LoggerFactory.getLogger(RuntimeStartupReport.class);

  static final String TASK_TYPE_PROPERTY = "CONNECTOR_AI_AGENT_TYPE";
  static final String SUBPROCESS_TYPE_PROPERTY = "CONNECTOR_AI_AGENT_JOB_WORKER_TYPE";

  private final Environment environment;

  RuntimeStartupReport(Environment environment) {
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  void report() {
    final boolean authEnabled =
        environment.getProperty(OrganizationAuthProperties.PREFIX + ".enabled", Boolean.class, false);
    final String taskType = environment.getProperty(TASK_TYPE_PROPERTY);
    final String subProcessType = environment.getProperty(SUBPROCESS_TYPE_PROPERTY);

    LOG.atInfo()
        .addKeyValue("runtimeVersion", versionOf(RuntimeStartupReport.class))
        .addKeyValue("connectorsVersion", versionOf(AiAgentFunction.class))
        .addKeyValue("javaVersion", Runtime.version())
        .addKeyValue("activeProfiles", Arrays.asList(environment.getActiveProfiles()))
        .addKeyValue("aiAgentTaskJobType", taskType)
        .addKeyValue("aiAgentSubProcessJobType", subProcessType)
        .addKeyValue("organizationAuthEnabled", authEnabled)
        .addKeyValue(
            "organizationAuthMode",
            authEnabled ? environment.getProperty(OrganizationAuthProperties.PREFIX + ".mode") : null)
        .log("Organization AI Agent connector runtime ready");

    if (!authEnabled) {
      LOG.warn(
          "Organization authentication is DISABLED ({}.enabled=false): Bedrock AI Agents use "
              + "Camunda's built-in AWS authentication, not the organization gateway.",
          OrganizationAuthProperties.PREFIX);
    }
    if (isStandardJobType(taskType) || isStandardJobType(subProcessType)) {
      LOG.atWarn()
          .addKeyValue("aiAgentTaskJobType", taskType)
          .addKeyValue("aiAgentSubProcessJobType", subProcessType)
          .log(
              "A standard Camunda AI Agent job type is in use: this runtime competes with the "
                  + "default connector runtime for those jobs. Set ORG_AI_AGENT_TASK_TYPE / "
                  + "ORG_AI_AGENT_SUBPROCESS_TYPE to custom types.");
    }
  }

  private static boolean isStandardJobType(String type) {
    return type != null && type.startsWith("io.camunda.agenticai:");
  }

  private static String versionOf(Class<?> type) {
    final String version = type.getPackage().getImplementationVersion();
    return version == null ? "unknown" : version;
  }
}
