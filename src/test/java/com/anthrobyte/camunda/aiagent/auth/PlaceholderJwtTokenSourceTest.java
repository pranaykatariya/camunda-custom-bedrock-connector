package com.anthrobyte.camunda.aiagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthrobyte.camunda.aiagent.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PlaceholderJwtTokenSourceTest {

  private final MutableClock clock = MutableClock.startingNow();
  private final ObjectMapper json = new ObjectMapper();

  private JsonNode part(String jwt, int index) throws Exception {
    return json.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.", -1)[index]), StandardCharsets.UTF_8));
  }

  @Test
  void producesUnsignedJwtWithExpiryMatchingTheToken() throws Exception {
    final AccessToken token = new PlaceholderJwtTokenSource(Duration.ofMinutes(5), clock).requestToken();

    final String[] parts = token.value().split("\\.", -1);
    assertThat(parts).hasSize(3);
    assertThat(parts[2]).isEmpty(); // unsigned
    assertThat(part(token.value(), 0).get("alg").asText()).isEqualTo("none");
    final JsonNode claims = part(token.value(), 1);
    assertThat(claims.get("iss").asText()).isEqualTo(PlaceholderJwtTokenSource.ISSUER);
    assertThat(claims.get("iat").asLong()).isEqualTo(clock.instant().getEpochSecond());
    assertThat(claims.get("exp").asLong()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)).getEpochSecond());
    assertThat(token.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
  }

  @Test
  void everyTokenIsDifferent() {
    final var source = new PlaceholderJwtTokenSource(Duration.ofMinutes(5), clock);
    assertThat(source.requestToken().value()).isNotEqualTo(source.requestToken().value());
  }

  @Test
  void warnsThatItIsAPlaceholderAndNeverLogsTheToken(CapturedOutput output) {
    final AccessToken token = new PlaceholderJwtTokenSource(Duration.ofMinutes(5), clock).requestToken();

    assertThat(output.getAll()).contains("PLACEHOLDER organization token in use").doesNotContain(token.value());
  }

  @Test
  void rejectsNonPositiveLifetime() {
    assertThatThrownBy(() -> new PlaceholderJwtTokenSource(Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
