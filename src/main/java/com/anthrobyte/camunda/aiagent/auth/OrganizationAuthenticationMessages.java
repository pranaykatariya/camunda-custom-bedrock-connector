package com.anthrobyte.camunda.aiagent.auth;

import java.util.regex.Pattern;

/** Builds the sanitized, user-facing messages for authentication failures. */
final class OrganizationAuthenticationMessages {

  /**
   * Only short lowercase machine-readable codes (for example the OAuth {@code error} value {@code
   * invalid_client}) or our own fixed phrases are ever echoed. Anything else is dropped, because it
   * could reflect secrets: tokens, JWTs and Base64 values contain uppercase letters, dots or
   * symbols.
   */
  private static final Pattern SAFE_DETAIL = Pattern.compile("[a-z0-9_ ]{1,64}");

  private OrganizationAuthenticationMessages() {}

  static String format(AuthenticationFailureReason reason, Integer httpStatus, String detail) {
    final var message =
        new StringBuilder(OrganizationAuthenticationException.MESSAGE_PREFIX)
            .append(": ")
            .append(reason.description());
    if (httpStatus != null) {
      message.append(" (HTTP ").append(httpStatus).append(')');
    }
    if (detail != null && SAFE_DETAIL.matcher(detail).matches()) {
      message.append(" [").append(detail).append(']');
    }
    return message.append('.').toString();
  }
}
