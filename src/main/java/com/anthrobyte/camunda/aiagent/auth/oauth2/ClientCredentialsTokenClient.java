package com.anthrobyte.camunda.aiagent.auth.oauth2;

import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.INTERRUPTED;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.MALFORMED_TOKEN_RESPONSE;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_ERROR;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_UNREACHABLE;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.TOKEN_REQUEST_REJECTED;

import com.anthrobyte.camunda.aiagent.auth.AccessToken;
import com.anthrobyte.camunda.aiagent.auth.AccessTokenSource;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationException;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs one OAuth 2.0 client-credentials token request. It holds no state and does no caching:
 * see {@link com.anthrobyte.camunda.aiagent.auth.CachingTokenAuthenticationProvider} for that.
 *
 * <p>Security properties:
 *
 * <ul>
 *   <li>The client secret is sent only to the configured token endpoint and never logged.
 *   <li>Response bodies are never logged or put into exception messages. Only the standard OAuth
 *       {@code error} code (for example {@code invalid_client}) is surfaced.
 *   <li>Redirects are not followed (configured on the injected {@link HttpClient}), so credentials
 *       cannot be forwarded to another host.
 * </ul>
 */
public class ClientCredentialsTokenClient implements AccessTokenSource {

  private static final Logger LOG = LoggerFactory.getLogger(ClientCredentialsTokenClient.class);
  private static final Pattern OAUTH_ERROR_CODE = Pattern.compile("[a-z0-9_]{1,40}");

  private final ClientCredentialsSettings settings;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ClientCredentialsTokenClient(
      ClientCredentialsSettings settings,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock) {
    this.settings = settings;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public AccessToken requestToken() {
    final long started = System.nanoTime();
    LOG.atDebug()
        .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
        .addKeyValue("clientId", settings.clientId())
        .addKeyValue("clientAuthentication", settings.clientAuthentication())
        .addKeyValue("scope", settings.scope())
        .addKeyValue("audience", settings.audience())
        .addKeyValue("additionalParameters", settings.additionalParameters().keySet())
        .log("Requesting organization authentication token");

    final HttpResponse<String> response = send(buildRequest());
    final int status = response.statusCode();
    final long durationMs = elapsedMillis(started);

    if (status >= 200 && status < 300) {
      final AccessToken token = parse(response.body());
      LOG.atDebug()
          .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
          .addKeyValue("httpStatus", status)
          .addKeyValue("durationMs", durationMs)
          .addKeyValue("expiresAt", token.expiresAt())
          .log("Organization authentication token received");
      return token;
    }

    final String oauthError = extractOAuthErrorCode(response.body());
    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
        .addKeyValue("httpStatus", status)
        .addKeyValue("oauthError", oauthError)
        .addKeyValue("durationMs", durationMs)
        .log("Organization authentication token request failed");

    if (status == 408 || status == 429 || status >= 500) {
      throw new OrganizationAuthenticationUnavailableException(
          TOKEN_ENDPOINT_ERROR, status, oauthError);
    }
    throw new OrganizationAuthenticationException(TOKEN_REQUEST_REJECTED, status, oauthError);
  }

  private HttpRequest buildRequest() {
    final Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "client_credentials");
    if (hasText(settings.scope())) {
      form.put("scope", settings.scope());
    }
    if (hasText(settings.audience())) {
      form.put("audience", settings.audience());
    }
    form.putAll(settings.additionalParameters());

    final var builder =
        HttpRequest.newBuilder(settings.tokenUri())
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json");

    switch (settings.clientAuthentication()) {
      case CLIENT_SECRET_BASIC -> {
        // RFC 6749 section 2.3.1: form-urlencode id and secret before Base64.
        final String basic =
            formEncode(settings.clientId()) + ":" + formEncode(settings.clientSecret());
        builder.header(
            "Authorization",
            "Basic "
                + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
      }
      case CLIENT_SECRET_POST -> {
        form.put("client_id", settings.clientId());
        form.put("client_secret", settings.clientSecret());
      }
    }

    final String body =
        form.entrySet().stream()
            .map(e -> formEncode(e.getKey()) + "=" + formEncode(e.getValue()))
            .collect(Collectors.joining("&"));
    return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      logTransportFailure(e);
      throw new OrganizationAuthenticationUnavailableException(TOKEN_ENDPOINT_TIMEOUT);
    } catch (IOException e) {
      logTransportFailure(e);
      throw new OrganizationAuthenticationUnavailableException(TOKEN_ENDPOINT_UNREACHABLE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.atWarn()
          .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
          .log("Organization authentication token request interrupted");
      throw new OrganizationAuthenticationUnavailableException(INTERRUPTED);
    }
  }

  private void logTransportFailure(Exception e) {
    // Transport exceptions describe host/port/timeouts only: no credentials.
    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
        .addKeyValue("error", e.getClass().getSimpleName())
        .setCause(e)
        .log("Organization authentication token request failed");
  }

  private AccessToken parse(String body) {
    final JsonNode json;
    try {
      json = objectMapper.readTree(body == null ? "" : body);
    } catch (IOException e) {
      throw malformed("response is not valid JSON");
    }
    if (json == null || !json.isObject()) {
      throw malformed("response is not a JSON object");
    }

    final JsonNode token = json.get("access_token");
    if (token == null || !token.isTextual() || token.asText().isBlank()) {
      throw malformed("access_token is missing");
    }

    final JsonNode tokenType = json.get("token_type");
    if (tokenType != null && tokenType.isTextual() && !"bearer".equalsIgnoreCase(tokenType.asText())) {
      LOG.atDebug()
          .addKeyValue("tokenType", tokenType.asText())
          .log("Token endpoint returned a non-bearer token type; using header template as configured");
    }

    final Duration lifetime = lifetime(json.get("expires_in"));
    return new AccessToken(token.asText(), clock.instant().plus(lifetime));
  }

  private Duration lifetime(JsonNode expiresIn) {
    if (expiresIn == null || expiresIn.isNull()) {
      // Worth seeing: if the real lifetime is shorter, the gateway will start answering 401.
      LOG.atInfo()
          .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
          .addKeyValue("defaultTokenLifetime", settings.defaultTokenLifetime())
          .log("Token response has no expires_in; assuming the configured default token lifetime");
      return settings.defaultTokenLifetime();
    }
    final long seconds;
    if (expiresIn.isNumber()) {
      seconds = expiresIn.asLong();
    } else if (expiresIn.isTextual() && expiresIn.asText().matches("\\d{1,10}")) {
      seconds = Long.parseLong(expiresIn.asText());
    } else {
      throw malformed("expires_in is not a number");
    }
    if (seconds <= 0) {
      throw malformed("expires_in is not positive");
    }
    return Duration.ofSeconds(seconds);
  }

  private OrganizationAuthenticationException malformed(String why) {
    LOG.atWarn()
        .addKeyValue("tokenEndpoint", settings.tokenUri().getHost())
        .addKeyValue("problem", why)
        .log("Organization authentication token response is malformed");
    return new OrganizationAuthenticationException(MALFORMED_TOKEN_RESPONSE, null, why);
  }

  private String extractOAuthErrorCode(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      final JsonNode error = objectMapper.readTree(body).get("error");
      // RFC 6749 error codes are short ASCII tokens. Anything else is not echoed or logged.
      return error != null && error.isTextual() && OAUTH_ERROR_CODE.matcher(error.asText()).matches()
          ? error.asText()
          : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private static String formEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
