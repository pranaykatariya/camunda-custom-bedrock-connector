package com.anthrobyte.camunda.aiagent.transport;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a URL belongs to an approved organization Bedrock gateway.
 *
 * <p>This check is what prevents credential exfiltration. The Bedrock custom endpoint is BPMN
 * input, so a modeler could point it anywhere. Organization credentials are only ever attached to
 * requests whose URL matches an allow-listed base URL.
 *
 * <p>A URL matches a base URL when scheme, host (case-insensitive) and effective port are equal,
 * and the path equals the base path or continues it on a {@code /} segment boundary. So {@code
 * https://gw/v1} matches {@code https://gw/v1/model/m/converse} but not {@code
 * https://gw/v10/...}. User-info URLs ({@code https://user@host}) never match.
 */
public final class GatewayEndpointMatcher {

  private static final Logger LOG = LoggerFactory.getLogger(GatewayEndpointMatcher.class);

  private final List<Base> bases;

  public GatewayEndpointMatcher(Collection<URI> allowedBaseUrls) {
    Objects.requireNonNull(allowedBaseUrls, "allowedBaseUrls must not be null");
    if (allowedBaseUrls.isEmpty()) {
      throw new IllegalArgumentException("At least one allowed gateway base URL is required");
    }
    this.bases = allowedBaseUrls.stream().map(GatewayEndpointMatcher::toBase).toList();
  }

  public boolean matches(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      return matches(URI.create(url.trim()));
    } catch (IllegalArgumentException e) {
      LOG.debug("Endpoint is not a parseable URL; not an organization gateway endpoint");
      return false;
    }
  }

  public boolean matches(URI candidate) {
    // normalize() resolves "." and ".." so that https://gw/v1/../admin cannot pass as /v1/...
    final URI uri = candidate == null ? null : candidate.normalize();
    if (uri == null
        || !uri.isAbsolute()
        || uri.getHost() == null
        || uri.getRawUserInfo() != null) {
      // Never log the URL itself here: it may carry user-info.
      LOG.atDebug()
          .addKeyValue(
              "problem",
              uri == null || !uri.isAbsolute() || uri.getHost() == null
                  ? "not an absolute URL with a host"
                  : "URL contains user-info")
          .log("Endpoint rejected by organization gateway allow list");
      return false;
    }
    final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    final String host = uri.getHost().toLowerCase(Locale.ROOT);
    final int port = effectivePort(scheme, uri.getPort());
    final String path = normalizePath(uri.getRawPath());

    final boolean matched =
        bases.stream()
            .anyMatch(
                base ->
                    base.scheme().equals(scheme)
                        && base.host().equals(host)
                        && base.port() == port
                        && (base.path().isEmpty()
                            || path.equals(base.path())
                            || path.startsWith(base.path() + "/")));
    if (!matched) {
      LOG.atDebug()
          .addKeyValue("candidate", new Base(scheme, host, port, path))
          .addKeyValue("allowed", bases)
          .addKeyValue("hint", mismatchHint(scheme, host, port, path))
          .log("Endpoint rejected by organization gateway allow list");
    }
    return matched;
  }

  /** Names the first component that differs from the closest allowed base, for troubleshooting. */
  private String mismatchHint(String scheme, String host, int port, String path) {
    if (bases.stream().noneMatch(b -> b.host().equals(host))) {
      return "host differs";
    }
    if (bases.stream().noneMatch(b -> b.host().equals(host) && b.scheme().equals(scheme))) {
      return "scheme differs";
    }
    if (bases.stream()
        .noneMatch(b -> b.host().equals(host) && b.scheme().equals(scheme) && b.port() == port)) {
      return "port differs";
    }
    return "path is not under an allowed base path";
  }

  private static Base toBase(URI uri) {
    if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "Allowed gateway base URL must be an absolute http(s) URL: " + uri);
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "Allowed gateway base URL must not contain user-info, query or fragment: "
              + uri.getHost());
    }
    final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw new IllegalArgumentException("Allowed gateway base URL must be http(s): " + uri);
    }
    return new Base(
        scheme,
        uri.getHost().toLowerCase(Locale.ROOT),
        effectivePort(scheme, uri.getPort()),
        normalizePath(uri.getRawPath()));
  }

  private static int effectivePort(String scheme, int port) {
    if (port != -1) {
      return port;
    }
    return "https".equals(scheme) ? 443 : 80;
  }

  private static String normalizePath(String rawPath) {
    if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
      return "";
    }
    String path = rawPath;
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  @Override
  public String toString() {
    return "GatewayEndpointMatcher" + bases;
  }

  private record Base(String scheme, String host, int port, String path) {
    @Override
    public String toString() {
      return scheme + "://" + host + ":" + port + path;
    }
  }
}
