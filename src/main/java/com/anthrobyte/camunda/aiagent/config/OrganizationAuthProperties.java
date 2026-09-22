package com.anthrobyte.camunda.aiagent.config;

import com.anthrobyte.camunda.aiagent.auth.oauth2.ClientCredentialsSettings.ClientAuthentication;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for organization authentication of the AI Agent's AWS Bedrock provider.
 *
 * <p>This uses its own prefix instead of {@code camunda.connector.agenticai.*}, so that a future
 * Camunda release cannot introduce a clashing property. Secrets must be supplied via environment
 * variables / Kubernetes Secrets (see {@code application.yml}); {@link #toString()} and every
 * nested {@code toString()} redact them.
 *
 * @param enabled master switch. When {@code false}, nothing from this project is active and the
 *     runtime behaves exactly like the standard Camunda AI Agent connector.
 * @param allowedEndpoints base URLs of the organization Bedrock gateway. Every Bedrock AI Agent
 *     must use one of them as its custom endpoint; organization credentials are sent nowhere else.
 * @param mode how credentials are obtained
 * @param retryOnUnauthorized retry a gateway call once with refreshed credentials after HTTP 401
 * @param allowInsecureHttp permit plain-http gateway or token URLs (local development only)
 * @param staticHeaders fixed headers sent to the gateway on every request, for example {@code
 *     Accept} and {@code Host}. In {@code STATIC_HEADERS} mode they are the credentials.
 * @param token how a token is put on the request and cached (token modes only)
 * @param placeholderJwt settings of the {@code PLACEHOLDER_JWT} mode
 * @param oauth2 OAuth 2.0 client-credentials settings
 */
@ConfigurationProperties(prefix = OrganizationAuthProperties.PREFIX)
public record OrganizationAuthProperties(
    @DefaultValue("false") boolean enabled,
    List<URI> allowedEndpoints,
    @DefaultValue("PLACEHOLDER_JWT") Mode mode,
    @DefaultValue("true") boolean retryOnUnauthorized,
    @DefaultValue("false") boolean allowInsecureHttp,
    List<Header> staticHeaders,
    @DefaultValue Token token,
    @DefaultValue PlaceholderJwt placeholderJwt,
    @DefaultValue OAuth2 oauth2) {

  public static final String PREFIX = "organization.ai-gateway.auth";

  private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Pattern HOST_HEADER_VALUE =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?");

  public enum Mode {
    /** A random, unsigned JWT: a stand-in until the organization token generation exists. */
    PLACEHOLDER_JWT,
    /** OAuth 2.0 client-credentials grant, with cached and refreshed access tokens. */
    OAUTH2_CLIENT_CREDENTIALS,
    /** Fixed headers from {@code static-headers}. */
    STATIC_HEADERS,
    /**
     * An {@code AccessTokenSource} bean (cached like the built-in token modes) or an {@code
     * OrganizationAuthenticationProvider} bean supplied by the application.
     */
    CUSTOM
  }

  public OrganizationAuthProperties {
    allowedEndpoints = allowedEndpoints == null ? List.of() : List.copyOf(allowedEndpoints);
    staticHeaders = staticHeaders == null ? List.of() : List.copyOf(staticHeaders);
  }

  /** A header whose value may be a secret. The value is never printed. */
  public record Header(String name, String value) {
    @Override
    public String toString() {
      return "Header{name=" + name + ", value=[REDACTED]}";
    }
  }

  /**
   * @param headerName header that carries the token
   * @param headerValueTemplate header value; {@code {token}} is replaced by the token
   * @param refreshSkew treat the token as expired this long before it really expires
   * @param refreshWaitTimeout how long a request waits for an in-flight token refresh
   */
  public record Token(
      @DefaultValue("x-bam-token") String headerName,
      @DefaultValue("{token}") String headerValueTemplate,
      @DefaultValue("PT60S") Duration refreshSkew,
      @DefaultValue("PT15S") Duration refreshWaitTimeout) {}

  /** @param lifetime expiry written into each placeholder JWT */
  public record PlaceholderJwt(@DefaultValue("PT5M") Duration lifetime) {}

  public record OAuth2(
      URI tokenUri,
      String clientId,
      String clientSecret,
      String scope,
      String audience,
      @DefaultValue("CLIENT_SECRET_BASIC") ClientAuthentication clientAuthentication,
      Map<String, String> additionalParameters,
      @DefaultValue("PT5S") Duration connectTimeout,
      @DefaultValue("PT10S") Duration requestTimeout,
      @DefaultValue("PT5M") Duration defaultTokenLifetime) {

    public OAuth2 {
      additionalParameters = additionalParameters == null ? Map.of() : Map.copyOf(additionalParameters);
    }

    @Override
    public String toString() {
      return "OAuth2{tokenUri="
          + tokenUri
          + ", clientId="
          + clientId
          + ", clientSecret=[REDACTED], scope="
          + scope
          + ", audience="
          + audience
          + ", clientAuthentication="
          + clientAuthentication
          + ", additionalParameters="
          + additionalParameters.keySet()
          + ", connectTimeout="
          + connectTimeout
          + ", requestTimeout="
          + requestTimeout
          + ", defaultTokenLifetime="
          + defaultTokenLifetime
          + "}";
    }
  }

  /** Whether the mode puts a cached token on the request (all modes but STATIC_HEADERS). */
  public boolean usesToken() {
    return mode != Mode.STATIC_HEADERS;
  }

  /**
   * Validates the configuration for the selected mode. Problems are reported by property
   * <i>name</i> (and the environment variable {@code application.yml} maps to it), never with
   * their values.
   *
   * @throws IllegalStateException listing every problem found
   */
  public void validate() {
    final List<String> problems = new ArrayList<>();

    if (allowedEndpoints.isEmpty()) {
      problems.add(
          PREFIX
              + ".allowed-endpoints must contain at least one Bedrock gateway base URL"
              + " (set ORG_AI_GATEWAY_URL)");
    }
    for (int i = 0; i < allowedEndpoints.size(); i++) {
      checkUrl(allowedEndpoints.get(i), PREFIX + ".allowed-endpoints[" + i + "]", problems);
    }

    for (int i = 0; i < staticHeaders.size(); i++) {
      final Header header = staticHeaders.get(i);
      final String prop = PREFIX + ".static-headers[" + i + "]";
      if (header.name() == null || !HEADER_NAME.matcher(header.name()).matches()) {
        problems.add(prop + ".name must be a valid HTTP header name");
      }
      if (header.value() == null || header.value().isBlank()) {
        problems.add(prop + ".value must not be blank (is the environment variable set?)");
      } else if ("host".equalsIgnoreCase(header.name())
          && !HOST_HEADER_VALUE.matcher(header.value()).matches()) {
        problems.add(
            prop + ".value must be host[:port] for the Host header, not a URL (check ORG_AI_GATEWAY_HOST_HEADER)");
      }
    }

    if (usesToken()) {
      validateToken(problems);
    }
    switch (mode) {
      case PLACEHOLDER_JWT -> requirePositive(placeholderJwt.lifetime(), PREFIX + ".placeholder-jwt.lifetime", problems);
      case OAUTH2_CLIENT_CREDENTIALS -> validateOAuth2(problems);
      case STATIC_HEADERS -> {
        if (staticHeaders.isEmpty()) {
          problems.add(PREFIX + ".static-headers must not be empty in STATIC_HEADERS mode");
        }
      }
      case CUSTOM -> {
        // validated by the auto-configuration: a custom bean must exist
      }
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid organization Bedrock gateway authentication configuration:\n - "
              + String.join("\n - ", problems));
    }
  }

  private void validateToken(List<String> problems) {
    final String p = PREFIX + ".token";
    if (token.headerName() == null || !HEADER_NAME.matcher(token.headerName()).matches()) {
      problems.add(p + ".header-name must be a valid HTTP header name (check ORG_AI_GATEWAY_TOKEN_HEADER)");
    }
    if (token.headerValueTemplate() == null || !token.headerValueTemplate().contains("{token}")) {
      problems.add(p + ".header-value-template must contain the placeholder {token}");
    }
    if (token.refreshSkew() == null || token.refreshSkew().isNegative()) {
      problems.add(p + ".refresh-skew must not be negative");
    }
    requirePositive(token.refreshWaitTimeout(), p + ".refresh-wait-timeout", problems);
  }

  private void validateOAuth2(List<String> problems) {
    final String p = PREFIX + ".oauth2";
    if (oauth2.tokenUri() == null) {
      problems.add(p + ".token-uri is required (set ORG_AI_TOKEN_URL)");
    } else {
      checkUrl(oauth2.tokenUri(), p + ".token-uri", problems);
    }
    if (isBlank(oauth2.clientId())) {
      problems.add(p + ".client-id is required (set ORG_AI_CLIENT_ID)");
    }
    if (isBlank(oauth2.clientSecret())) {
      problems.add(p + ".client-secret is required (set ORG_AI_CLIENT_SECRET)");
    }
    requirePositive(oauth2.connectTimeout(), p + ".connect-timeout", problems);
    requirePositive(oauth2.requestTimeout(), p + ".request-timeout", problems);
    requirePositive(oauth2.defaultTokenLifetime(), p + ".default-token-lifetime", problems);
  }

  private void checkUrl(URI uri, String property, List<String> problems) {
    if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
      problems.add(property + " must be an absolute URL");
      return;
    }
    final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (scheme.equals("http") && !allowInsecureHttp) {
      problems.add(
          property + " must use https (set " + PREFIX + ".allow-insecure-http=true for local development only)");
    } else if (!scheme.equals("https") && !scheme.equals("http")) {
      problems.add(property + " must be an http(s) URL");
    }
    if (uri.getRawUserInfo() != null) {
      problems.add(property + " must not contain user-info (credentials in URLs are not allowed)");
    }
  }

  private static void requirePositive(Duration duration, String property, List<String> problems) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      problems.add(property + " must be a positive duration");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
