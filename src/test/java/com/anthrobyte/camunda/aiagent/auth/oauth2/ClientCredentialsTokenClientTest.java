package com.anthrobyte.camunda.aiagent.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason;
import com.anthrobyte.camunda.aiagent.auth.AccessToken;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationException;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationUnavailableException;
import com.anthrobyte.camunda.aiagent.auth.oauth2.ClientCredentialsSettings.ClientAuthentication;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import com.anthrobyte.camunda.aiagent.support.MutableClock;
import com.anthrobyte.camunda.aiagent.support.TokenResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.NonRetriableException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ClientCredentialsTokenClientTest {

  private static final String CLIENT_ID = "camunda-ai-agent";
  private static final String CLIENT_SECRET = "s3cr3t:with/special+chars";
  private static final String TOKEN_PATH = "/oauth2/token";

  private final FakeHttpServer idp = new FakeHttpServer();
  private final MutableClock clock = MutableClock.startingNow();
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @AfterEach
  void tearDown() {
    idp.close();
    httpClient.close();
  }

  private ClientCredentialsTokenClient client(
      URI tokenUri, ClientAuthentication auth, Duration requestTimeout) {
    return new ClientCredentialsTokenClient(
        new ClientCredentialsSettings(
            tokenUri,
            CLIENT_ID,
            CLIENT_SECRET,
            "llm.invoke",
            "https://ai-gateway.example.com",
            auth,
            Map.of("resource", "chat"),
            requestTimeout,
            Duration.ofMinutes(5)),
        httpClient,
        new ObjectMapper(),
        clock);
  }

  private ClientCredentialsTokenClient client() {
    return client(idp.uri(TOKEN_PATH), ClientAuthentication.CLIENT_SECRET_BASIC, Duration.ofSeconds(2));
  }

  private static Map<String, String> form(String body) {
    return Arrays.stream(body.split("&"))
        .map(kv -> kv.split("=", 2))
        .collect(
            Collectors.toMap(
                kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
  }

  @Test
  void successfulAuthenticationWithClientSecretBasic() {
    idp.on(TOKEN_PATH, Response.json(200, TokenResponses.token("tok-1", 3600)));

    final AccessToken token = client().requestToken();

    assertThat(token.value()).isEqualTo("tok-1");
    assertThat(token.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));

    final var request = idp.requests(TOKEN_PATH).getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
    // RFC 6749 2.3.1: id and secret are form-encoded before base64
    final String basic =
        new String(
            Base64.getDecoder().decode(request.header("Authorization").substring("Basic ".length())),
            StandardCharsets.UTF_8);
    final String[] idAndSecret = basic.split(":", 2);
    assertThat(URLDecoder.decode(idAndSecret[0], StandardCharsets.UTF_8)).isEqualTo(CLIENT_ID);
    assertThat(URLDecoder.decode(idAndSecret[1], StandardCharsets.UTF_8)).isEqualTo(CLIENT_SECRET);

    assertThat(form(request.body()))
        .containsEntry("grant_type", "client_credentials")
        .containsEntry("scope", "llm.invoke")
        .containsEntry("audience", "https://ai-gateway.example.com")
        .containsEntry("resource", "chat")
        .doesNotContainKeys("client_id", "client_secret");
  }

  @Test
  void clientSecretPostSendsCredentialsInBody() {
    idp.on(TOKEN_PATH, Response.json(200, TokenResponses.token("tok-1", 60)));

    client(idp.uri(TOKEN_PATH), ClientAuthentication.CLIENT_SECRET_POST, Duration.ofSeconds(2))
        .requestToken();

    final var request = idp.requests(TOKEN_PATH).getFirst();
    assertThat(request.header("Authorization")).isNull();
    assertThat(form(request.body()))
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("client_secret", CLIENT_SECRET);
  }

  @Test
  void missingExpiresInUsesDefaultLifetime() {
    idp.on(TOKEN_PATH, Response.json(200, "{\"access_token\":\"tok\",\"token_type\":\"Bearer\"}"));

    assertThat(client().requestToken().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
  }

  @Test
  void expiresInAsStringIsAccepted() {
    idp.on(TOKEN_PATH, Response.json(200, "{\"access_token\":\"tok\",\"expires_in\":\"120\"}"));

    assertThat(client().requestToken().expiresAt()).isEqualTo(clock.instant().plusSeconds(120));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403})
  void rejectedClientCredentialsArePermanentFailures(int status) {
    idp.on(TOKEN_PATH, Response.json(status, "{\"error\":\"invalid_client\"}"));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOf(OrganizationAuthenticationException.class)
        .isInstanceOf(NonRetriableException.class)
        .hasMessage(
            "Organization Bedrock gateway authentication failed: the token endpoint rejected the client "
                + "credentials (HTTP %d) [invalid_client].",
            status)
        .satisfies(
            e -> {
              final var ex = (OrganizationAuthenticationException) e;
              assertThat(ex.reason()).isEqualTo(AuthenticationFailureReason.TOKEN_REQUEST_REJECTED);
              assertThat(ex.httpStatus()).hasValue(status);
              assertThat(ex.getCause()).isNull();
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503})
  void serverSideFailuresAreTransient(int status) {
    idp.on(TOKEN_PATH, Response.json(status, "upstream down"));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOf(OrganizationAuthenticationUnavailableException.class)
        .isNotInstanceOf(NonRetriableException.class)
        .extracting(e -> ((OrganizationAuthenticationUnavailableException) e).reason())
        .isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_ERROR);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json at all",
        "[]",
        "{}",
        "{\"access_token\":\"\"}",
        "{\"access_token\":42}",
        "{\"access_token\":\"tok\",\"expires_in\":\"soon\"}",
        "{\"access_token\":\"tok\",\"expires_in\":0}",
        "{\"access_token\":\"tok\",\"expires_in\":-5}"
      })
  void malformedTokenResponseIsPermanentFailure(String body) {
    idp.on(TOKEN_PATH, Response.json(200, body));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOf(OrganizationAuthenticationException.class)
        .extracting(e -> ((OrganizationAuthenticationException) e).reason())
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN_RESPONSE);
  }

  @Test
  void timeoutIsTransient() {
    idp.on(
        TOKEN_PATH,
        Response.json(200, TokenResponses.token("late", 60)).delayedBy(Duration.ofSeconds(2)));

    final var client =
        client(idp.uri(TOKEN_PATH), ClientAuthentication.CLIENT_SECRET_BASIC, Duration.ofMillis(200));

    assertThatThrownBy(client::requestToken)
        .isInstanceOf(OrganizationAuthenticationUnavailableException.class)
        .hasMessageContaining("did not respond in time")
        .extracting(e -> ((OrganizationAuthenticationUnavailableException) e).reason())
        .isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT);
  }

  @Test
  void connectionFailureIsTransient() throws Exception {
    final int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    final var client =
        client(
            URI.create("http://127.0.0.1:" + closedPort + TOKEN_PATH),
            ClientAuthentication.CLIENT_SECRET_BASIC,
            Duration.ofSeconds(1));

    assertThatThrownBy(client::requestToken)
        .isInstanceOf(OrganizationAuthenticationUnavailableException.class)
        .extracting(e -> ((OrganizationAuthenticationUnavailableException) e).reason())
        .isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_UNREACHABLE);
  }

  @Test
  void sensitiveResponsePayloadIsNeitherEchoedNorLogged(CapturedOutput output) {
    // A misbehaving IdP that reflects the secret and a token in its error body and error code.
    final String reflective =
        "{\"error\":\"" + CLIENT_SECRET + "\",\"access_token\":\"leaked-token\",\"secret\":\""
            + CLIENT_SECRET + "\"}";
    idp.on(TOKEN_PATH, Response.json(401, reflective));

    assertThatThrownBy(() -> client().requestToken())
        .isInstanceOf(OrganizationAuthenticationException.class)
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain(CLIENT_SECRET)
                    .doesNotContain("leaked-token")
                    .endsWith("(HTTP 401)."));

    assertThat(output.getAll())
        .contains("Organization authentication token request failed")
        .doesNotContain(CLIENT_SECRET)
        .doesNotContain("leaked-token")
        .doesNotContain(Base64.getEncoder().encodeToString(CLIENT_ID.getBytes()));
  }

  @Test
  void settingsToStringRedactsSecret() {
    final var settings =
        new ClientCredentialsSettings(
            URI.create("https://idp"),
            CLIENT_ID,
            CLIENT_SECRET,
            null,
            null,
            ClientAuthentication.CLIENT_SECRET_BASIC,
            Map.of("x", "y"),
            Duration.ofSeconds(1),
            Duration.ofMinutes(1));

    assertThat(settings.toString()).doesNotContain(CLIENT_SECRET).contains("[REDACTED]");
    assertThat(new AccessToken("tok-123", clock.instant()).toString()).doesNotContain("tok-123");
  }
}
