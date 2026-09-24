package com.barclays.groupcontrol.co.camunda.connectors.auth;

import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.ISSUED_AT;
import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.LIFETIME_SECONDS;
import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.PASSWORD;
import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.TOKEN_PATH;
import static com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses.USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamToken;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BarclaysAuthenticationException;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BarclaysAuthenticationUnavailableException;
import com.barclays.groupcontrol.co.camunda.connectors.support.BamResponses;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BamTokenClientTest {

  private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
  private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);

  private final FakeHttpServer bam = new FakeHttpServer();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @AfterEach
  void tearDown() {
    bam.close();
    httpClient.close();
  }

  private BamTokenClient client(URI tokenUrl, Duration requestTimeout) {
    return new BamTokenClient(
        new BamTokenSettings(tokenUrl, USERNAME, PASSWORD, requestTimeout, DEFAULT_LIFETIME),
        httpClient,
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private BamTokenClient client() {
    return client(bam.uri(TOKEN_PATH), Duration.ofSeconds(5));
  }

  @Test
  void getsTheTokenWithBasicAuthentication() {
    bam.on(TOKEN_PATH, BamResponses.issuing());

    final BamToken token = client().requestToken();

    assertThat(token.value()).isEqualTo(BamResponses.jwt(1));
    final var request = bam.requests(TOKEN_PATH).getFirst();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.header("Authorization")).isEqualTo(BamResponses.basicAuthorization());
    assertThat(request.header("Accept")).isEqualTo("application/json");
    assertThat(request.body()).isEmpty();
  }

  @Test
  void expiryIsTheTokenLifetimeAppliedToTheLocalClock() {
    // iat/exp are years before NOW: a clock-skew-sensitive client would see an expired token.
    bam.on(TOKEN_PATH, BamResponses.issuing());

    assertThat(client().requestToken().expiresAt()).isEqualTo(NOW.plusSeconds(LIFETIME_SECONDS));
    assertThat(NOW.getEpochSecond()).isGreaterThan(ISSUED_AT + LIFETIME_SECONDS);
  }

  @Test
  void expiryFallsBackToExpThenToTheDefaultLifetime() {
    final long exp = NOW.plusSeconds(300).getEpochSecond();
    bam.on(TOKEN_PATH, Response.json(200, BamResponses.body(BamResponses.jwt("{\"exp\":" + exp + "}"))));
    assertThat(client().requestToken().expiresAt()).isEqualTo(Instant.ofEpochSecond(exp));

    bam.on(TOKEN_PATH, Response.json(200, BamResponses.body("opaque-token")));
    assertThat(client().requestToken().expiresAt()).isEqualTo(NOW.plus(DEFAULT_LIFETIME));
  }

  @Test
  void rejectedCredentialsArePermanentAndSanitized(CapturedOutput output) {
    bam.on(TOKEN_PATH, Response.json(401, "{\"error\":\"Invalid credentials " + PASSWORD + "\"}"));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOfSatisfying(
            BarclaysAuthenticationException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(AuthenticationFailureReason.TOKEN_REQUEST_REJECTED);
              assertThat(e.httpStatus()).hasValue(401);
              assertThat(e.getMessage()).doesNotContain(PASSWORD).doesNotContain("Invalid credentials");
              assertThat(e.getCause()).isNull();
            });
    assertThat(output.getAll()).contains("BAM token request failed").doesNotContain(PASSWORD);
  }

  @Test
  void serverErrorsAndThrottlingAreTransient() {
    for (int status : new int[] {408, 429, 500, 503}) {
      bam.on(TOKEN_PATH, Response.json(status, "{}"));
      assertThatThrownBy(() -> client().requestToken())
          .isInstanceOfSatisfying(
              BarclaysAuthenticationUnavailableException.class,
              e -> {
                assertThat(e.reason()).isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_ERROR);
                assertThat(e.httpStatus()).hasValue(status);
              });
    }
  }

  @Test
  void redirectsAreNotFollowed() {
    bam.on(
        TOKEN_PATH,
        new Response(302, null, Map.of("Location", bam.baseUrl() + "/elsewhere"), Duration.ZERO));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessageContaining("(HTTP 302)");
    assertThat(bam.requests()).hasSize(1);
  }

  @Test
  void malformedResponsesArePermanent() {
    for (String body : new String[] {"not json", "[]", "{}", "{\"bamToken\": \"\"}", "{\"bamToken\": 42}"}) {
      bam.on(TOKEN_PATH, Response.json(200, body));
      assertThatThrownBy(() -> client().requestToken())
          .isInstanceOfSatisfying(
              BarclaysAuthenticationException.class,
              e -> assertThat(e.reason()).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN_RESPONSE));
    }
  }

  @Test
  void timeoutAndUnreachableEndpointAreTransient() {
    bam.on(
        TOKEN_PATH,
        (req, n) -> Response.json(200, BamResponses.body(BamResponses.jwt(n))).delayedBy(Duration.ofSeconds(2)));
    assertThatThrownBy(() -> client(bam.uri(TOKEN_PATH), Duration.ofMillis(200)).requestToken())
        .isInstanceOfSatisfying(
            BarclaysAuthenticationUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT));

    assertThatThrownBy(() -> client(URI.create("http://127.0.0.1:1" + TOKEN_PATH), Duration.ofSeconds(2)).requestToken())
        .isInstanceOfSatisfying(
            BarclaysAuthenticationUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_UNREACHABLE));
  }

  @Test
  void neverLogsCredentials(CapturedOutput output) {
    bam.on(TOKEN_PATH, BamResponses.issuing());

    final BamToken token = client().requestToken();

    assertThat(output.getAll())
        .doesNotContain(PASSWORD)
        .doesNotContain(BamResponses.basicAuthorization().substring("Basic ".length()))
        .doesNotContain(token.value());
    assertThat(client().toString()).doesNotContain(PASSWORD);
    assertThat(new BamTokenSettings(bam.uri(TOKEN_PATH), USERNAME, PASSWORD, Duration.ofSeconds(1), DEFAULT_LIFETIME).toString())
        .doesNotContain(PASSWORD)
        .doesNotContain(USERNAME);
  }
}
