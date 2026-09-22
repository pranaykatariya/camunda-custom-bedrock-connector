package com.anthrobyte.camunda.aiagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Placeholder.</b> Generates a random, <i>unsigned</i> JWT ({@code alg: none}) so that the
 * runtime can be wired and tested end to end before the organization's real token generation
 * exists. No gateway should accept this token.
 *
 * <p>Replace it by registering your own {@link AccessTokenSource} bean and setting {@code
 * organization.ai-gateway.auth.mode=CUSTOM}. A warning is logged at startup while this class is in
 * use.
 */
public final class PlaceholderJwtTokenSource implements AccessTokenSource {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceholderJwtTokenSource.class);
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final String HEADER = "{\"alg\":\"none\",\"typ\":\"JWT\"}";

  static final String ISSUER = "org-ai-agent-connector-runtime";

  private final Duration lifetime;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public PlaceholderJwtTokenSource(Duration lifetime, Clock clock) {
    if (lifetime == null || lifetime.isNegative() || lifetime.isZero()) {
      throw new IllegalArgumentException("lifetime must be a positive duration");
    }
    this.lifetime = lifetime;
    this.clock = clock;
    LOG.atWarn()
        .addKeyValue("tokenLifetime", lifetime)
        .log(
            "PLACEHOLDER organization token in use: a random unsigned JWT is sent to the Bedrock "
                + "gateway. Provide an AccessTokenSource bean and set mode=CUSTOM before production.");
  }

  @Override
  public AccessToken requestToken() {
    final Instant now = clock.instant();
    final Instant expiresAt = now.plus(lifetime);
    final byte[] id = new byte[16];
    random.nextBytes(id);
    final String claims =
        "{\"iss\":\"%s\",\"sub\":\"placeholder\",\"iat\":%d,\"exp\":%d,\"jti\":\"%s\"}"
            .formatted(
                ISSUER, now.getEpochSecond(), expiresAt.getEpochSecond(), HexFormat.of().formatHex(id));
    final String jwt = encode(HEADER) + "." + encode(claims) + ".";
    LOG.atDebug().addKeyValue("expiresAt", expiresAt).log("Placeholder organization token generated");
    return new AccessToken(jwt, expiresAt);
  }

  private static String encode(String json) {
    return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "PlaceholderJwtTokenSource{lifetime=" + lifetime + "}";
  }
}
