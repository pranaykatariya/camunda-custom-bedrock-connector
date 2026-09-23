package com.anthrobyte.camunda.aiagent.auth;

import java.time.Instant;
import java.util.Objects;

/** A BAM token and its absolute expiry. {@link #toString()} never prints the token. */
public record BamToken(String value, Instant expiresAt) {

  public BamToken {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    return "BamToken{value=[REDACTED], expiresAt=" + expiresAt + "}";
  }
}
