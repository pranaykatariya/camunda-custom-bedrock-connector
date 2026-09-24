package com.barclays.groupcontrol.co.camunda.connectors.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The HTTP headers that authenticate one request against the Barclays Bedrock gateway.
 *
 * <p>Instances are immutable and compared by <b>identity</b>: {@link
 * BamTokenCache#invalidate(GatewayCredentials)} uses identity to invalidate only the exact
 * credentials the gateway rejected, never a newer token another thread already obtained.
 *
 * <p>{@link #toString()} prints header <i>names</i> only. Header values are credentials and must
 * never be logged.
 */
public final class GatewayCredentials {

  private final Map<String, String> headers;
  private final Set<String> lowerCaseHeaderNames;

  public GatewayCredentials(Map<String, String> headers) {
    Objects.requireNonNull(headers, "headers must not be null");
    if (headers.isEmpty()) {
      throw new IllegalArgumentException("At least one authentication header is required");
    }
    final var copy = new LinkedHashMap<String, String>();
    headers.forEach(
        (name, value) -> {
          if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Authentication header name must not be blank");
          }
          if (value == null || value.isEmpty()) {
            // Only the name is reported: the value is a credential.
            throw new IllegalArgumentException(
                "Authentication header '%s' must have a value".formatted(name));
          }
          copy.put(name, value);
        });
    this.headers = Collections.unmodifiableMap(copy);
    this.lowerCaseHeaderNames =
        copy.keySet().stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  /** Header name to value, in insertion order. Values are secrets. */
  public Map<String, String> headers() {
    return headers;
  }

  /** Case-insensitive check (HTTP header names are case-insensitive). */
  public boolean containsHeader(String headerName) {
    return headerName != null && lowerCaseHeaderNames.contains(headerName.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return "GatewayCredentials{headers=" + headers.keySet() + ", values=[REDACTED]}";
  }
}
