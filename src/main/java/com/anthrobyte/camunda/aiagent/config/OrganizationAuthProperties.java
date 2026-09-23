package com.anthrobyte.camunda.aiagent.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>The token is requested from the BAM token endpoint ({@code BamTokenClient}) and cached.
 *
 * @param enabled master switch. When {@code false}, nothing from this project is active and the
 *     runtime behaves exactly like the standard Camunda AI Agent connector.
 * @param retryOnUnauthorized retry a gateway call once with refreshed credentials after HTTP 401
 * @param staticHeaders fixed headers sent to the gateway on every request next to the token, for
 *     example {@code Accept} and {@code Host}
 * @param allowInsecureHttp permit a plain-http BAM token URL (local development only)
 * @param token how a token is put on the request and cached
 * @param bam the BAM token endpoint and its credentials
 */
@ConfigurationProperties(prefix = OrganizationAuthProperties.PREFIX)
public record OrganizationAuthProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean retryOnUnauthorized,
    List<Header> staticHeaders,
    @DefaultValue("false") boolean allowInsecureHttp,
    @DefaultValue Token token,
    @DefaultValue Bam bam) {

  public static final String PREFIX = "organization.ai-gateway.auth";

  private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Pattern HOST_HEADER_VALUE =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?");

  public OrganizationAuthProperties {
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

  /**
   * @param tokenUrl BAM token endpoint ({@code GET}, HTTP Basic authentication)
   * @param username Basic authentication user name (secret)
   * @param password Basic authentication password (secret)
   * @param connectTimeout TCP connect timeout for the token endpoint
   * @param requestTimeout overall timeout for one token request
   * @param defaultTokenLifetime assumed when a token carries no usable {@code iat}/{@code exp}
   */
  public record Bam(
      URI tokenUrl,
      String username,
      String password,
      @DefaultValue("PT5S") Duration connectTimeout,
      @DefaultValue("PT10S") Duration requestTimeout,
      @DefaultValue("PT10M") Duration defaultTokenLifetime) {

    @Override
    public String toString() {
      return "Bam{tokenUrl="
          + tokenUrl
          + ", username=[REDACTED], password=[REDACTED], connectTimeout="
          + connectTimeout
          + ", requestTimeout="
          + requestTimeout
          + ", defaultTokenLifetime="
          + defaultTokenLifetime
          + "}";
    }
  }

  /**
   * Validates the configuration. Problems are reported by property <i>name</i> (and the
   * environment variable {@code application.yml} maps to it), never with their values.
   *
   * @throws IllegalStateException listing every problem found
   */
  public void validate() {
    final List<String> problems = new ArrayList<>();

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

    validateToken(problems);
    validateBam(problems);

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

  private void validateBam(List<String> problems) {
    final String p = PREFIX + ".bam";
    final URI url = bam.tokenUrl();
    if (url == null) {
      problems.add(p + ".token-url is required (set ORG_AI_BAM_TOKEN_URL)");
    } else if (!url.isAbsolute() || url.getHost() == null) {
      problems.add(p + ".token-url must be an absolute URL (check ORG_AI_BAM_TOKEN_URL)");
    } else {
      final String scheme = url.getScheme().toLowerCase(Locale.ROOT);
      if (scheme.equals("http") && !allowInsecureHttp) {
        problems.add(
            p + ".token-url must use https (set " + PREFIX + ".allow-insecure-http=true for local development only)");
      } else if (!scheme.equals("https") && !scheme.equals("http")) {
        problems.add(p + ".token-url must be an http(s) URL");
      }
      if (url.getRawUserInfo() != null) {
        problems.add(p + ".token-url must not contain user-info (credentials in URLs are not allowed)");
      }
    }
    if (bam.username() == null || bam.username().isBlank()) {
      problems.add(p + ".username is required (set ORG_AI_BAM_USERNAME)");
    } else if (bam.username().contains(":")) {
      // RFC 7617: the Basic user-id cannot contain a colon.
      problems.add(p + ".username must not contain ':' (check ORG_AI_BAM_USERNAME)");
    }
    if (bam.password() == null || bam.password().isEmpty()) {
      problems.add(p + ".password is required (set ORG_AI_BAM_PASSWORD)");
    }
    requirePositive(bam.connectTimeout(), p + ".connect-timeout", problems);
    requirePositive(bam.requestTimeout(), p + ".request-timeout", problems);
    requirePositive(bam.defaultTokenLifetime(), p + ".default-token-lifetime", problems);
  }

  private static void requirePositive(Duration duration, String property, List<String> problems) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      problems.add(property + " must be a positive duration");
    }
  }
}
