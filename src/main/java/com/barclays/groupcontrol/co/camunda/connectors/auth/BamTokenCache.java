package com.barclays.groupcontrol.co.camunda.connectors.auth;

import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.INTERRUPTED;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT;
import static com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason.TOKEN_SOURCE_FAILED;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Barclays credentials for the Bedrock gateway: a BAM token from {@link BamTokenClient},
 * cached and shared by all AI Agent jobs of this pod, sent in the token header next to the static
 * headers. {@link com.barclays.groupcontrol.co.camunda.connectors.transport.AuthenticatingSdkHttpClient} calls
 * {@link #getCredentials()} for every HTTP attempt and {@link #invalidate(GatewayCredentials)}
 * after a gateway 401.
 *
 * <h2>Concurrency model</h2>
 *
 * <ul>
 *   <li><b>Fast path (lock-free).</b> While the cached token is valid, {@link #getCredentials()} is
 *       a single volatile read. Requests holding a valid token never wait for anything.
 *   <li><b>Single-flight refresh.</b> When the token is missing or expired, exactly one thread
 *       requests a new one while holding {@link #refreshLock}. Threads that arrive in the meantime
 *       wait for that result, then take the fresh token via a double-check instead of issuing
 *       their own request. This avoids a refresh stampede when many AI Agent jobs start at once.
 *   <li><b>Shared failure.</b> If the in-flight refresh fails, the threads that were waiting for it
 *       get the same failure instead of each retrying serially against a failing BAM endpoint.
 *       Threads that arrive later try again.
 *   <li><b>Bounded waiting.</b> Waiting for the lock is bounded, so a hung BAM endpoint cannot
 *       block job worker threads forever.
 *   <li><b>Precise invalidation.</b> {@link #invalidate(GatewayCredentials)} compares by identity
 *       and clears the cache only if the rejected credentials are still the current ones. Many
 *       concurrent 401s therefore cause one refresh, not one per request.
 * </ul>
 *
 * <h2>Expiry</h2>
 *
 * A token counts as expired {@code refreshSkew} before its real expiry, so requests in flight
 * never carry a token that expires mid-request. For very short-lived tokens the skew is capped at
 * half the lifetime, so a token is never considered expired on arrival.
 */
public class BamTokenCache implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BamTokenCache.class);

  static final String TOKEN_PLACEHOLDER = "{token}";

  /** Waiting this long for a concurrent refresh is logged at INFO: BAM is slow. */
  private static final long SLOW_REFRESH_WAIT_MS = 1_000;

  private final BamTokenClient tokenClient;
  private final String headerName;
  private final String headerValueTemplate;
  private final Map<String, String> additionalHeaders;
  private final Duration refreshSkew;
  private final Duration refreshWaitTimeout;
  private final Clock clock;

  private final AtomicReference<CachedCredentials> current = new AtomicReference<>();
  private final ReentrantLock refreshLock = new ReentrantLock();
  private final AtomicLong completedRefreshes = new AtomicLong();
  private final AtomicLong successfulRefreshes = new AtomicLong();
  private volatile FailedRefresh lastFailure;

  /**
   * @param tokenClient fetches new tokens; closed by {@link #close()}
   * @param additionalHeaders sent with every request next to the token header, in insertion order
   */
  public BamTokenCache(
      BamTokenClient tokenClient,
      String headerName,
      String headerValueTemplate,
      Map<String, String> additionalHeaders,
      Duration refreshSkew,
      Duration refreshWaitTimeout,
      Clock clock) {
    this.tokenClient = Objects.requireNonNull(tokenClient);
    this.headerName = requireText(headerName, "headerName");
    this.headerValueTemplate = requireText(headerValueTemplate, "headerValueTemplate");
    if (!headerValueTemplate.contains(TOKEN_PLACEHOLDER)) {
      throw new IllegalArgumentException(
          "headerValueTemplate must contain the placeholder " + TOKEN_PLACEHOLDER);
    }
    this.additionalHeaders =
        additionalHeaders == null ? Map.of() : new LinkedHashMap<>(additionalHeaders);
    this.refreshSkew = Objects.requireNonNull(refreshSkew);
    this.refreshWaitTimeout = Objects.requireNonNull(refreshWaitTimeout);
    this.clock = Objects.requireNonNull(clock);
    LOG.atDebug()
        .addKeyValue("headerName", headerName)
        .addKeyValue("additionalHeaders", this.additionalHeaders.keySet())
        .addKeyValue("refreshSkew", refreshSkew)
        .addKeyValue("refreshWaitTimeout", refreshWaitTimeout)
        .log("Caching Barclays authentication provider created");
  }

  /**
   * Returns the credentials to attach to the next gateway request. Called once per HTTP attempt:
   * a single volatile read while the cached token is valid.
   */
  public GatewayCredentials getCredentials() {
    final CachedCredentials cached = current.get();
    if (cached != null && cached.isValidAt(clock.instant())) {
      LOG.atTrace()
          .addKeyValue("refreshAt", cached.validUntil())
          .log("Using cached Barclays authentication token");
      return cached.credentials();
    }
    LOG.atDebug()
        .addKeyValue("cause", cached == null ? "no cached token" : "cached token expired")
        .addKeyValue("refreshAt", cached == null ? null : cached.validUntil())
        .log("Barclays authentication token refresh required");
    return refresh();
  }

  /**
   * Called when the gateway rejected {@code rejected} with HTTP 401. Discards the cached token, but
   * only if it is still the current one.
   */
  public void invalidate(GatewayCredentials rejected) {
    final CachedCredentials cached = current.get();
    if (cached != null
        && cached.credentials() == rejected
        && current.compareAndSet(cached, null)) {
      LOG.atInfo()
          .addKeyValue("refreshAt", cached.validUntil())
          .log("Barclays authentication token invalidated after gateway rejection");
    } else {
      LOG.debug(
          "Ignoring invalidation of Barclays credentials that are no longer current "
              + "(already refreshed by another request)");
    }
  }

  private GatewayCredentials refresh() {
    // Snapshot before waiting: a refresh that finishes after this point is one we waited for.
    final long refreshesBeforeWaiting = completedRefreshes.get();
    final long waitStarted = System.nanoTime();
    acquireRefreshLock();
    try {
      final long waitedMs = Duration.ofNanos(System.nanoTime() - waitStarted).toMillis();
      final CachedCredentials cached = current.get();
      if (cached != null && cached.isValidAt(clock.instant())) {
        // another thread refreshed while we waited
        (waitedMs >= SLOW_REFRESH_WAIT_MS ? LOG.atInfo() : LOG.atDebug())
            .addKeyValue("waitedMs", waitedMs)
            .log("Using Barclays authentication token refreshed by a concurrent request");
        return cached.credentials();
      }

      final FailedRefresh failure = lastFailure;
      if (failure != null && failure.sequence() > refreshesBeforeWaiting) {
        // the refresh we waited for failed: share its outcome instead of hammering the source
        final RuntimeException shared = failure.copyOfError();
        LOG.atDebug()
            .addKeyValue("waitedMs", waitedMs)
            .addKeyValue("reason", reasonOf(shared))
            .log("Reusing the failure of a concurrent Barclays authentication token refresh");
        throw shared;
      }

      return fetchAndCache();
    } finally {
      refreshLock.unlock();
    }
  }

  private GatewayCredentials fetchAndCache() {
    final long started = System.nanoTime();
    try {
      final BamToken token = requestToken();
      final CachedCredentials fresh = cache(token);
      current.set(fresh);
      lastFailure = null;
      completedRefreshes.incrementAndGet();
      final long refreshCount = successfulRefreshes.incrementAndGet();
      LOG.atInfo()
          .addKeyValue("expiresAt", token.expiresAt())
          .addKeyValue("refreshAt", fresh.validUntil())
          .addKeyValue("tokenLifetime", Duration.between(clock.instant(), token.expiresAt()))
          .addKeyValue("headers", fresh.credentials().headers().keySet())
          .addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
          // A steadily climbing count at short intervals means tokens are being invalidated (401s).
          .addKeyValue("refreshCount", refreshCount)
          .log("Barclays authentication token refreshed");
      return fresh.credentials();
    } catch (BarclaysAuthenticationException | BarclaysAuthenticationUnavailableException e) {
      lastFailure = new FailedRefresh(completedRefreshes.incrementAndGet(), e);
      LOG.atWarn()
          .addKeyValue("reason", reasonOf(e))
          .addKeyValue("retriable", e instanceof BarclaysAuthenticationUnavailableException)
          .addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
          .log("Barclays authentication token refresh failed");
      throw e;
    }
  }

  /** Calls BAM and turns any unexpected exception into a sanitized, permanent failure. */
  private BamToken requestToken() {
    final BamToken token;
    try {
      token = tokenClient.requestToken();
    } catch (BarclaysAuthenticationException | BarclaysAuthenticationUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      // Only the type is logged: an unexpected exception's message could hold secrets.
      LOG.atWarn()
          .addKeyValue("error", e.getClass().getName())
          .log("Barclays token source failed unexpectedly");
      throw new BarclaysAuthenticationException(TOKEN_SOURCE_FAILED);
    }
    if (token == null) {
      throw new BarclaysAuthenticationException(TOKEN_SOURCE_FAILED, null, "no token returned");
    }
    return token;
  }

  private static Object reasonOf(RuntimeException e) {
    if (e instanceof BarclaysAuthenticationException a) {
      return a.reason();
    }
    if (e instanceof BarclaysAuthenticationUnavailableException u) {
      return u.reason();
    }
    return e.getClass().getSimpleName();
  }

  private CachedCredentials cache(BamToken token) {
    final Instant now = clock.instant();
    final Duration lifetime = Duration.between(now, token.expiresAt());
    final Duration skew =
        refreshSkew.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshSkew;
    if (skew.compareTo(refreshSkew) < 0) {
      LOG.atDebug()
          .addKeyValue("tokenLifetime", lifetime)
          .addKeyValue("configuredRefreshSkew", refreshSkew)
          .addKeyValue("effectiveRefreshSkew", skew)
          .log("Short-lived token: refresh skew capped at half the token lifetime");
    }

    final var headers = new LinkedHashMap<String, String>(additionalHeaders);
    headers.put(headerName, headerValueTemplate.replace(TOKEN_PLACEHOLDER, token.value()));
    return new CachedCredentials(new GatewayCredentials(headers), token.expiresAt().minus(skew));
  }

  private void acquireRefreshLock() {
    try {
      if (!refreshLock.tryLock(refreshWaitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.atWarn()
            .addKeyValue("waitTimeout", refreshWaitTimeout)
            .log("Timed out waiting for Barclays authentication token refresh");
        throw new BarclaysAuthenticationUnavailableException(TOKEN_ENDPOINT_TIMEOUT);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while waiting for Barclays authentication token refresh");
      throw new BarclaysAuthenticationUnavailableException(INTERRUPTED);
    }
  }

  @Override
  public void close() {
    current.set(null);
    tokenClient.close();
    LOG.atInfo()
        .addKeyValue("refreshCount", successfulRefreshes.get())
        .log("Barclays authentication provider closed; cached token discarded");
  }

  @Override
  public String toString() {
    return "BamTokenCache{tokenClient="
        + tokenClient
        + ", headerName="
        + headerName
        + ", additionalHeaders="
        + additionalHeaders.keySet()
        + ", refreshSkew="
        + refreshSkew
        + "}";
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private record CachedCredentials(GatewayCredentials credentials, Instant validUntil) {
    boolean isValidAt(Instant now) {
      return now.isBefore(validUntil);
    }
  }

  private record FailedRefresh(long sequence, RuntimeException error) {
    /** A fresh instance per thread, so no two threads share (and mutate) one exception object. */
    RuntimeException copyOfError() {
      return error instanceof BarclaysAuthenticationException e
          ? e.copy()
          : ((BarclaysAuthenticationUnavailableException) error).copy();
    }
  }
}
