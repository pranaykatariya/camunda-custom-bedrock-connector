package com.anthrobyte.camunda.aiagent.support;

/** Canned OAuth 2.0 token endpoint responses. */
public final class TokenResponses {

  private TokenResponses() {}

  public static String token(String accessToken, long expiresIn) {
    return """
        {"access_token": "%s", "token_type": "Bearer", "expires_in": %d}
        """
        .formatted(accessToken, expiresIn);
  }
}
