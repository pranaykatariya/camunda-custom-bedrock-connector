package com.anthrobyte.camunda.aiagent.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class GatewayEndpointMatcherTest {

  private final GatewayEndpointMatcher matcher =
      new GatewayEndpointMatcher(
          List.of(
              URI.create("https://bedrock-gateway.example.com/bedrock/v1/"),
              URI.create("https://llm.internal:8443")));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://bedrock-gateway.example.com/bedrock/v1",
        "https://bedrock-gateway.example.com/bedrock/v1/",
        "https://bedrock-gateway.example.com/bedrock/v1/model/m/converse",
        "https://BEDROCK-GATEWAY.example.com/bedrock/v1/model/m/converse",
        "https://bedrock-gateway.example.com:443/bedrock/v1/model/m/converse?api-version=1",
        "HTTPS://bedrock-gateway.example.com/bedrock/v1",
        "https://llm.internal:8443/",
        "https://llm.internal:8443/anything/v1/chat/completions",
        "  https://bedrock-gateway.example.com/bedrock/v1  "
      })
  void matchesGatewayUrls(String url) {
    assertThat(matcher.matches(url)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://bedrock-gateway.example.com/bedrock/v1", // scheme downgrade
        "https://bedrock-gateway.example.com/bedrock/v10", // not a segment boundary
        "https://bedrock-gateway.example.com/bedrock", // above base path
        "https://bedrock-gateway.example.com/bedrock/v1/../../admin", // traversal
        "https://bedrock-gateway.example.com.evil.com/bedrock/v1", // suffix host
        "https://evil.com/bedrock/v1",
        "https://bedrock-gateway.example.com:8443/bedrock/v1", // other port
        "https://llm.internal/v1", // default port 443 != 8443
        "https://user:pw@bedrock-gateway.example.com/bedrock/v1", // user-info
        "https://bedrock-runtime.eu-central-1.amazonaws.com/v1",
        "not a url",
        "/relative/path",
        ""
      })
  void doesNotMatchOtherUrls(String url) {
    assertThat(matcher.matches(url)).isFalse();
  }

  @Test
  void nullNeverMatches() {
    assertThat(matcher.matches((String) null)).isFalse();
    assertThat(matcher.matches((URI) null)).isFalse();
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> new GatewayEndpointMatcher(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayEndpointMatcher(List.of(URI.create("/relative"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayEndpointMatcher(List.of(URI.create("ftp://host/x"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new GatewayEndpointMatcher(List.of(URI.create("https://host/x?token=abc"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("abc");
  }
}
