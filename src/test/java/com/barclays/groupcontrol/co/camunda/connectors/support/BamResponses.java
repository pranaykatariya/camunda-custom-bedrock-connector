package com.barclays.groupcontrol.co.camunda.connectors.support;

import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.Responder;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A fake BAM token endpoint: canned tokens, responses and the properties that point at it. */
public final class BamResponses {

  public static final String TOKEN_PATH = "/api/token";
  public static final String USERNAME = "bam-user";
  public static final String PASSWORD = "Bam-Password-Secret-123";
  /** Long in the past on purpose: the expiry must come from {@code exp - iat}, not the pod clock. */
  public static final long ISSUED_AT = 1_700_000_000L;
  public static final long LIFETIME_SECONDS = 600;

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /**
   * The header segment every issued token starts with. Asserting that a log does not contain it
   * catches any leaked token, including a truncated one.
   */
  public static final String JWT_PREFIX = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

  private BamResponses() {}

  /** The n-th token the fake endpoint issues: an HS256-shaped JWT valid for 10 minutes. */
  public static String jwt(int n) {
    return jwt("{\"sub\":\"bam-user\",\"iat\":%d,\"exp\":%d,\"jti\":\"barclays-jwt-%d\"}"
        .formatted(ISSUED_AT, ISSUED_AT + LIFETIME_SECONDS, n));
  }

  public static String jwt(String claimsJson) {
    return JWT_PREFIX + "." + b64(claimsJson) + "." + b64("signature");
  }

  public static String body(String token) {
    return "{\"bamToken\": \"" + token + "\"}";
  }

  /** Issues {@link #jwt(int)} for the n-th call. */
  public static Responder issuing() {
    return (request, n) -> Response.json(200, body(jwt(n)));
  }

  /** {@code Basic base64(USERNAME:PASSWORD)}, as the endpoint expects it. */
  public static String basicAuthorization() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
  }

  /** Properties pointing Barclays authentication at {@code bam} (plain http, so allowed). */
  public static String[] properties(FakeHttpServer bam) {
    return new String[] {
      "barclays.ai-gateway.auth.allow-insecure-http=true",
      "barclays.ai-gateway.auth.bam.token-url=" + bam.baseUrl() + TOKEN_PATH,
      "barclays.ai-gateway.auth.bam.username=" + USERNAME,
      "barclays.ai-gateway.auth.bam.password=" + PASSWORD
    };
  }

  private static String b64(String s) {
    return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }
}
