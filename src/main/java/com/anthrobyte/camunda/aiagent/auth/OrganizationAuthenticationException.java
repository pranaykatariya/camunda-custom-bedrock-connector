package com.anthrobyte.camunda.aiagent.auth;

import dev.langchain4j.exception.NonRetriableException;
import java.util.OptionalInt;

/**
 * Permanent organization authentication failure, such as rejected client credentials, a gateway
 * 401/403, or a Bedrock endpoint that is not an approved gateway.
 *
 * <p>Extends LangChain4j's {@link NonRetriableException}, so LangChain4j never retries it. The AI
 * Agent's {@code Langchain4JAiFrameworkAdapter} wraps it into a {@code ConnectorException} with
 * error code {@code FAILED_MODEL_CALL}. This is the same path as every other model call failure, so
 * Camunda's job retry and incident semantics are unchanged.
 *
 * <p>The message never contains tokens, secrets, header values or response payloads. It carries
 * no cause on purpose, so no secret-bearing exception travels along with it.
 */
public class OrganizationAuthenticationException extends NonRetriableException {

  public static final String MESSAGE_PREFIX = "Organization Bedrock gateway authentication failed";

  private final AuthenticationFailureReason reason;
  private final Integer httpStatus;
  private final String detail;

  public OrganizationAuthenticationException(AuthenticationFailureReason reason) {
    this(reason, null, null);
  }

  public OrganizationAuthenticationException(
      AuthenticationFailureReason reason, Integer httpStatus, String detail) {
    super(OrganizationAuthenticationMessages.format(reason, httpStatus, detail));
    this.reason = reason;
    this.httpStatus = httpStatus;
    this.detail = detail;
  }

  public AuthenticationFailureReason reason() {
    return reason;
  }

  public OptionalInt httpStatus() {
    return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
  }

  /** A fresh instance with the same reason, status and message. */
  public OrganizationAuthenticationException copy() {
    return new OrganizationAuthenticationException(reason, httpStatus, detail);
  }
}
