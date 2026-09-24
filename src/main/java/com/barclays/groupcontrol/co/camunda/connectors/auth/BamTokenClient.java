package com.barclays.groupcontrol.co.camunda.connectors.auth;

import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.INTERRUPTED;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.MALFORMED_TOKEN_RESPONSE;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_ERROR;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_UNREACHABLE;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_REQUEST_REJECTED;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests one BAM token: {@code GET <token-url>} with HTTP Basic authentication, answered by
 * {@code {"bamToken": "<JWT>"}}. It does no caching: {@link BamTokenCache} does that and is the
 * only caller. Owns the HTTP client and closes it in {@link #close()}.
 *
 * <p><b>Expiry.</b> Taken from the token's own claims. With {@code iat} and {@code exp} the
 * lifetime ({@code exp - iat}) is applied to the local clock, so clock skew between this pod and
 * the BAM service cannot make a fresh token look expired (or an expired one look valid). With only
 * {@code exp}, that instant is used. Otherwise the configured default lifetime is assumed. The
 * signature is not verified: the gateway does that.
 *
 * <p>Security properties:
 *
 * <ul>
 *   <li>The credentials are sent only to the configured token URL and never logged.
 *   <li>Response bodies and tokens are never logged or put into exception messages.
 *   <li>Redirects are not followed (configured on the injected {@link HttpClient}), so the
 *       credentials cannot be forwarded to another host.
 * </ul>
 */
public class BamTokenClient implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BamTokenClient.class);
  private static final String TOKEN_FIELD = "bamToken";

  private final BamTokenSettings settings;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String authorization;

  public BamTokenClient(
      BamTokenSettings settings, HttpClient httpClient, ObjectMapper objectMapper, Clock clock) {
    this.settings = settings;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
    // RFC 7617: Basic base64(user-id ":" password), UTF-8.
    this.authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Requests one new token. Failures are thrown as {@link BarclaysAuthenticationException}
   * (permanent) or {@link BarclaysAuthenticationUnavailableException} (transient), sanitized.
   */
  public BamToken requestToken() {
    final long started = System.nanoTime();
    LOG.atDebug()
        .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
        .log("Requesting BAM token");

    final HttpResponse<String> response = send(buildRequest());
    final int status = response.statusCode();
    final long durationMs = elapsedMillis(started);

    if (status >= 200 && status < 300) {
      final BamToken token = parse(response.body());
      LOG.atDebug()
          .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
          .addKeyValue("httpStatus", status)
          .addKeyValue("durationMs", durationMs)
          .addKeyValue("expiresAt", token.expiresAt())
          .log("BAM token received");
      return token;
    }

    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
        .addKeyValue("httpStatus", status)
        .addKeyValue("durationMs", durationMs)
        .log("BAM token request failed");

    if (status == 408 || status == 429 || status >= 500) {
      throw new BarclaysAuthenticationUnavailableException(TOKEN_ENDPOINT_ERROR, status, null);
    }
    throw new BarclaysAuthenticationException(TOKEN_REQUEST_REJECTED, status, null);
  }

  private HttpRequest buildRequest() {
    return HttpRequest.newBuilder(settings.tokenUrl())
        .timeout(settings.requestTimeout())
        .header("Authorization", authorization)
        .header("Accept", "application/json")
        .GET()
        .build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      logTransportFailure(e);
      throw new BarclaysAuthenticationUnavailableException(TOKEN_ENDPOINT_TIMEOUT);
    } catch (IOException e) {
      logTransportFailure(e);
      throw new BarclaysAuthenticationUnavailableException(TOKEN_ENDPOINT_UNREACHABLE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.atWarn()
          .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
          .log("BAM token request interrupted");
      throw new BarclaysAuthenticationUnavailableException(INTERRUPTED);
    }
  }

  private void logTransportFailure(Exception e) {
    // Transport exceptions describe host/port/timeouts only: no credentials.
    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
        .addKeyValue("error", e.getClass().getSimpleName())
        .setCause(e)
        .log("BAM token request failed");
  }

  private BamToken parse(String body) {
    final JsonNode json;
    try {
      json = objectMapper.readTree(body == null ? "" : body);
    } catch (IOException e) {
      throw malformed("response is not valid JSON");
    }
    if (json == null || !json.isObject()) {
      throw malformed("response is not a JSON object");
    }
    final JsonNode token = json.get(TOKEN_FIELD);
    if (token == null || !token.isTextual() || token.asText().isBlank()) {
      throw malformed("bam token is missing");
    }
    return new BamToken(token.asText(), expiresAt(token.asText()));
  }

  private Instant expiresAt(String jwt) {
    final Instant now = clock.instant();
    final JsonNode claims = claims(jwt);
    final long iat = epochSeconds(claims, "iat");
    final long exp = epochSeconds(claims, "exp");
    if (iat > 0 && exp > iat) {
      return now.plusSeconds(exp - iat);
    }
    if (exp > 0 && Instant.ofEpochSecond(exp).isAfter(now)) {
      return Instant.ofEpochSecond(exp);
    }
    // Worth seeing: if the real lifetime is shorter, the gateway will start answering 401.
    LOG.atInfo()
        .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
        .addKeyValue("defaultTokenLifetime", settings.defaultTokenLifetime())
        .log("BAM token has no usable iat/exp claims; assuming the configured default token lifetime");
    return now.plus(settings.defaultTokenLifetime());
  }

  /** The JWT payload, or {@code null} if the token is not a decodable JWT. */
  private JsonNode claims(String jwt) {
    final String[] parts = jwt.split("\\.", -1);
    if (parts.length != 3) {
      return null;
    }
    try {
      return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    } catch (IOException | IllegalArgumentException e) {
      return null;
    }
  }

  private static long epochSeconds(JsonNode claims, String name) {
    final JsonNode value = claims == null ? null : claims.get(name);
    return value != null && value.canConvertToLong() ? value.asLong() : -1;
  }

  private BarclaysAuthenticationException malformed(String why) {
    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUrl().getHost())
        .addKeyValue("problem", why)
        .log("BAM token response is malformed");
    return new BarclaysAuthenticationException(MALFORMED_TOKEN_RESPONSE, null, why);
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  @Override
  public void close() {
    httpClient.close();
  }

  @Override
  public String toString() {
    return "BamTokenClient{tokenEndpoint=" + settings.tokenUrl().getHost() + "}";
  }
}
