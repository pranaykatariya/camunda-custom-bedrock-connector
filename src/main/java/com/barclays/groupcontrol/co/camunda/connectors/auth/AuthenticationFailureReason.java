package com.barclays.groupcontrol.co.camunda.connectors.auth;

/**
 * Why Barclays authentication failed. It appears in exception messages and logs, so each
 * description must stay free of secrets.
 */
public enum AuthenticationFailureReason {

  // --- permanent: retrying the same request will not help ---------------------------------
  TOKEN_REQUEST_REJECTED("the token endpoint rejected the client credentials"),
  MALFORMED_TOKEN_RESPONSE("the token endpoint returned a malformed response"),
  TOKEN_SOURCE_FAILED("the Barclays token source failed"),
  GATEWAY_REJECTED_CREDENTIALS("the Bedrock gateway rejected the Barclays credentials"),
  GATEWAY_ACCESS_DENIED("the Bedrock gateway denied access for the Barclays credentials"),
  ENDPOINT_NOT_CONFIGURED("no usable Bedrock gateway endpoint is configured on the element"),

  // --- transient: a later attempt may succeed ------------------------------------------------
  TOKEN_ENDPOINT_TIMEOUT("the token endpoint did not respond in time"),
  TOKEN_ENDPOINT_UNREACHABLE("the token endpoint could not be reached"),
  TOKEN_ENDPOINT_ERROR("the token endpoint is temporarily unavailable"),
  INTERRUPTED("the token request was interrupted");

  private final String description;

  AuthenticationFailureReason(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }
}
