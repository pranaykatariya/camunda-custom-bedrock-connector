package com.anthrobyte.camunda.aiagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayCredentialsTest {

  @Test
  void toStringNeverContainsValues() {
    final var credentials =
        new GatewayCredentials(
            Map.of("X-Client-ID", "client-123", "X-Client-Secret", "very-secret-value"));

    assertThat(credentials.toString())
        .contains("X-Client-ID", "X-Client-Secret", "[REDACTED]")
        .doesNotContain("client-123", "very-secret-value");
  }

  @Test
  void headerLookupIsCaseInsensitive() {
    final var credentials = GatewayCredentials.of("Authorization", "Bearer t");

    assertThat(credentials.containsHeader("authorization")).isTrue();
    assertThat(credentials.containsHeader("AUTHORIZATION")).isTrue();
    assertThat(credentials.containsHeader("X-Other")).isFalse();
    assertThat(credentials.containsHeader(null)).isFalse();
  }

  @Test
  void rejectsMissingValuesWithoutEchoingThem() {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Client-Secret", "");

    assertThatThrownBy(() -> new GatewayCredentials(headers))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("X-Client-Secret");
    assertThatThrownBy(() -> new GatewayCredentials(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void headersAreImmutable() {
    final var credentials = GatewayCredentials.of("Authorization", "Bearer t");

    assertThatThrownBy(() -> credentials.headers().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void staticHeadersProviderReturnsFixedCredentialsAndCannotRefresh() {
    final var provider =
        new StaticHeadersAuthenticationProvider(
            Map.of("X-Client-ID", "id", "X-Client-Secret", "secret"));

    assertThat(provider.getCredentials()).isSameAs(provider.getCredentials());
    assertThat(provider.getCredentials().headers())
        .containsEntry("X-Client-ID", "id")
        .containsEntry("X-Client-Secret", "secret");
    assertThat(provider.supportsRefresh()).isFalse();
    assertThat(provider.toString()).doesNotContain("secret\"").doesNotContain("=secret");
  }

  @Test
  void exceptionMessagesAreSanitized() {
    final var e =
        new OrganizationAuthenticationException(
            AuthenticationFailureReason.TOKEN_REQUEST_REJECTED, 401, "Bearer eyJhbGciOi.payload.sig");

    // Only short machine-readable detail codes are echoed; anything else is dropped.
    assertThat(e.getMessage())
        .isEqualTo(
            "Organization Bedrock gateway authentication failed: the token endpoint rejected the client credentials (HTTP 401).");
    assertThat(e.getCause()).isNull();
    assertThat(e.copy()).isNotSameAs(e).hasMessage(e.getMessage());
  }
}
