package com.barclays.groupcontrol.co.camunda.connectors.auth;

import dev.langchain4j.exception.NonRetriableException;
import java.util.OptionalInt;

/**
 * Permanent Barclays authentication failure, such as rejected client credentials, a gateway
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
public class BarclaysAuthenticationException extends NonRetriableException {

  public static final String MESSAGE_PREFIX = "Barclays Bedrock gateway authentication failed";

  private final AuthenticationFailureReason reason;
  private final Integer httpStatus;
  private final String detail;

  public BarclaysAuthenticationException(AuthenticationFailureReason reason) {
    this(reason, null, null);
  }

  public BarclaysAuthenticationException(
      AuthenticationFailureReason reason, Integer httpStatus, String detail) {
    super(BarclaysAuthenticationMessages.format(reason, httpStatus, detail));
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
  public BarclaysAuthenticationException copy() {
    return new BarclaysAuthenticationException(reason, httpStatus, detail);
  }
}
