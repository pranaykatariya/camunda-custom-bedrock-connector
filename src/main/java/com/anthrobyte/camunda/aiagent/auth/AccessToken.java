package com.anthrobyte.camunda.aiagent.auth;

import java.time.Instant;
import java.util.Objects;

/** An access token and its absolute expiry. {@link #toString()} never prints the token. */
public record AccessToken(String value, Instant expiresAt) {

  public AccessToken {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    return "AccessToken{value=[REDACTED], expiresAt=" + expiresAt + "}";
  }
}
