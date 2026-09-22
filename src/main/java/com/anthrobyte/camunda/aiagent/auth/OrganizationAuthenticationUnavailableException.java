package com.anthrobyte.camunda.aiagent.auth;

import dev.langchain4j.exception.LangChain4jException;
import java.util.OptionalInt;

/**
 * Transient organization authentication failure: token endpoint timeout, connection failure, HTTP
 * 5xx/429, or interruption.
 *
 * <p>Deliberately <b>not</b> a {@code NonRetriableException}: the job fails with {@code
 * FAILED_MODEL_CALL} and Camunda's normal job retries try again later.
 *
 * <p>Like {@link OrganizationAuthenticationException}, it carries no cause and a sanitized message.
 * The low-level cause is logged where it happens instead.
 */
public class OrganizationAuthenticationUnavailableException extends LangChain4jException {

  private final AuthenticationFailureReason reason;
  private final Integer httpStatus;
  private final String detail;

  public OrganizationAuthenticationUnavailableException(AuthenticationFailureReason reason) {
    this(reason, null, null);
  }

  public OrganizationAuthenticationUnavailableException(
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
  public OrganizationAuthenticationUnavailableException copy() {
    return new OrganizationAuthenticationUnavailableException(reason, httpStatus, detail);
  }
}
