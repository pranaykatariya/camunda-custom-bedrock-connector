package com.barclays.groupcontrol.co.camunda.connectors.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barclays.groupcontrol.co.camunda.connectors.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class BamTokenCacheTest {

  private final MutableClock clock = MutableClock.startingNow();
  private final BamTokenClient tokenClient = mock(BamTokenClient.class);
  private final ExecutorService pool = Executors.newFixedThreadPool(64);

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private BamTokenCache cache(Duration refreshWaitTimeout) {
    return new BamTokenCache(
        tokenClient,
        "Authorization",
        "Bearer {token}",
        Map.of("X-Client-ID", "camunda-ai-agent"),
        Duration.ofSeconds(60),
        refreshWaitTimeout,
        clock);
  }

  private BamTokenCache cache() {
    return cache(Duration.ofSeconds(10));
  }

  private BamToken token(String value, Duration lifetime) {
    return new BamToken(value, clock.instant().plus(lifetime));
  }

  /** Token client stub that counts calls and returns tokens tok-1, tok-2, ... */
  private AtomicInteger countingTokens(Duration lifetime, Duration latency) {
    final var calls = new AtomicInteger();
    when(tokenClient.requestToken())
        .thenAnswer(
            inv -> {
              final int n = calls.incrementAndGet();
              if (!latency.isZero()) {
                Thread.sleep(latency);
              }
              return token("tok-" + n, lifetime);
            });
    return calls;
  }

  private <T> List<T> runConcurrently(int threads, Supplier<T> action) throws Exception {
    final var start = new CountDownLatch(1);
    final List<Future<T>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              (Callable<T>)
                  () -> {
                    start.await();
                    return action.get();
                  }));
    }
    start.countDown();
    final List<T> results = new ArrayList<>();
    for (Future<T> f : futures) {
      results.add(f.get(20, TimeUnit.SECONDS));
    }
    return results;
  }

  // --- basics ------------------------------------------------------------------------------------

  @Test
  void buildsHeadersFromTokenAndAdditionalHeaders() {
    countingTokens(Duration.ofHours(1), Duration.ZERO);

    final GatewayCredentials credentials = cache().getCredentials();

    assertThat(credentials.headers())
        .containsEntry("Authorization", "Bearer tok-1")
        .containsEntry("X-Client-ID", "camunda-ai-agent");
    assertThat(credentials.toString()).doesNotContain("tok-1").contains("Authorization");
  }

  @Test
  void cachesTokenUntilExpiry() {
    final var calls = countingTokens(Duration.ofHours(1), Duration.ZERO);
    final var cache = cache();

    final var first = cache.getCredentials();
    for (int i = 0; i < 100; i++) {
      assertThat(cache.getCredentials()).isSameAs(first);
    }
    assertThat(calls).hasValue(1);
  }

  @Test
  void refreshesExpiredTokenRespectingRefreshSkew() {
    final var calls = countingTokens(Duration.ofMinutes(10), Duration.ZERO);
    final var cache = cache();
    assertThat(cache.getCredentials().headers()).containsEntry("Authorization", "Bearer tok-1");

    // still valid 1s before (expiry - skew)
    clock.advance(Duration.ofMinutes(9).minusSeconds(1));
    assertThat(cache.getCredentials().headers()).containsEntry("Authorization", "Bearer tok-1");

    // at (expiry - 60s skew) the token is treated as expired and refreshed
    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.getCredentials().headers()).containsEntry("Authorization", "Bearer tok-2");
    assertThat(calls).hasValue(2);
  }

  @Test
  void skewIsCappedForShortLivedTokens() {
    // 30s token with 60s skew must not be "expired on arrival" (skew capped to 15s)
    final var calls = countingTokens(Duration.ofSeconds(30), Duration.ZERO);
    final var cache = cache();

    cache.getCredentials();
    clock.advance(Duration.ofSeconds(14));
    cache.getCredentials();
    assertThat(calls).hasValue(1);

    clock.advance(Duration.ofSeconds(1));
    cache.getCredentials();
    assertThat(calls).hasValue(2);
  }

  // --- invalidation ------------------------------------------------------------------------------

  @Test
  void invalidateCurrentCredentialsForcesRefresh() {
    final var calls = countingTokens(Duration.ofHours(1), Duration.ZERO);
    final var cache = cache();

    final var rejected = cache.getCredentials();
    cache.invalidate(rejected);

    assertThat(cache.getCredentials().headers()).containsEntry("Authorization", "Bearer tok-2");
    assertThat(calls).hasValue(2);
  }

  @Test
  void invalidatingStaleCredentialsDoesNotDiscardNewerToken() {
    final var calls = countingTokens(Duration.ofHours(1), Duration.ZERO);
    final var cache = cache();

    final var stale = cache.getCredentials();
    cache.invalidate(stale);
    final var fresh = cache.getCredentials();

    cache.invalidate(stale); // a late 401 for the old token
    assertThat(cache.getCredentials()).isSameAs(fresh);
    assertThat(calls).hasValue(2);
  }

  @Test
  void concurrentInvalidationsOfSameTokenCauseSingleRefresh() throws Exception {
    final var calls = countingTokens(Duration.ofHours(1), Duration.ofMillis(100));
    final var cache = cache();
    final var rejected = cache.getCredentials();

    final var results =
        runConcurrently(
            32,
            () -> {
              cache.invalidate(rejected);
              return cache.getCredentials();
            });

    assertThat(results).allSatisfy(c -> assertThat(c).isNotSameAs(rejected));
    // Invalidation is identity-based: only the first invalidate clears the cache.
    assertThat(calls).hasValue(2);
  }

  // --- concurrency -------------------------------------------------------------------------------

  @Test
  void concurrentInitialRequestsShareOneTokenRequest() throws Exception {
    final var calls = countingTokens(Duration.ofHours(1), Duration.ofMillis(300));
    final var cache = cache();

    final var results = runConcurrently(64, cache::getCredentials);

    assertThat(calls).hasValue(1);
    assertThat(results).allSatisfy(c -> assertThat(c).isSameAs(results.getFirst()));
  }

  @Test
  void concurrentRefreshAfterExpiryIsSingleFlight() throws Exception {
    final var calls = countingTokens(Duration.ofMinutes(5), Duration.ofMillis(300));
    final var cache = cache();
    cache.getCredentials();

    clock.advance(Duration.ofMinutes(5));
    final var results = runConcurrently(64, cache::getCredentials);

    assertThat(calls).hasValue(2);
    assertThat(results)
        .allSatisfy(c -> assertThat(c.headers()).containsEntry("Authorization", "Bearer tok-2"));
  }

  // --- failures ----------------------------------------------------------------------------------

  @Test
  void authenticationFailurePropagatesAndIsNotCached() {
    final var calls = new AtomicInteger();
    when(tokenClient.requestToken())
        .thenAnswer(
            inv -> {
              if (calls.incrementAndGet() == 1) {
                throw new BarclaysAuthenticationException(
                    AuthenticationFailureReason.TOKEN_REQUEST_REJECTED, 401, "invalid_client");
              }
              return token("tok-ok", Duration.ofHours(1));
            });
    final var cache = cache();

    assertThatThrownBy(cache::getCredentials)
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessageContaining("invalid_client");

    // the next (later) call tries again
    assertThat(cache.getCredentials().headers()).containsEntry("Authorization", "Bearer tok-ok");
  }

  @Test
  void waitersShareTheFailureOfTheRefreshTheyWaitedFor() throws Exception {
    final var calls = new AtomicInteger();
    when(tokenClient.requestToken())
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              Thread.sleep(300);
              throw new BarclaysAuthenticationUnavailableException(
                  AuthenticationFailureReason.TOKEN_ENDPOINT_ERROR, 503, null);
            });
    final var cache = cache();

    final var outcomes =
        runConcurrently(
            32,
            () -> {
              try {
                cache.getCredentials();
                return "success";
              } catch (BarclaysAuthenticationUnavailableException e) {
                return e.reason().name();
              }
            });

    assertThat(outcomes).containsOnly("TOKEN_ENDPOINT_ERROR");
    // No stampede against the failing IdP: far fewer requests than callers.
    assertThat(calls.get()).isLessThanOrEqualTo(2);
  }

  @Test
  void waitingForARefreshIsBounded() throws Exception {
    final var release = new CountDownLatch(1);
    final var entered = new CountDownLatch(1);
    when(tokenClient.requestToken())
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await();
              return token("tok", Duration.ofHours(1));
            });
    final var cache = cache(Duration.ofMillis(200));

    final Future<GatewayCredentials> slow = pool.submit(cache::getCredentials);
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(cache::getCredentials)
        .isInstanceOf(BarclaysAuthenticationUnavailableException.class)
        .extracting(e -> ((BarclaysAuthenticationUnavailableException) e).reason())
        .isEqualTo(AuthenticationFailureReason.TOKEN_ENDPOINT_TIMEOUT);

    release.countDown();
    assertThat(slow.get(5, TimeUnit.SECONDS).headers()).containsEntry("Authorization", "Bearer tok");
  }

  @Test
  void sharedFailuresAreDistinctExceptionInstances() throws Exception {
    when(tokenClient.requestToken())
        .thenAnswer(
            inv -> {
              Thread.sleep(200);
              throw new BarclaysAuthenticationException(
                  AuthenticationFailureReason.TOKEN_REQUEST_REJECTED, 401, "invalid_client");
            });
    final var cache = cache();

    final List<Throwable> errors =
        runConcurrently(
            8,
            () -> {
              try {
                cache.getCredentials();
                return null;
              } catch (RuntimeException e) {
                return (Throwable) e;
              }
            });

    assertThat(errors).doesNotContainNull().doesNotHaveDuplicates();
    assertThat(errors)
        .allSatisfy(e -> assertThat(e).hasMessageContaining("invalid_client").hasMessageContaining("401"));
  }

  @Test
  void unexpectedClientExceptionBecomesSanitizedPermanentFailure() {
    when(tokenClient.requestToken()).thenThrow(new IllegalStateException("key material sk-123 missing"));

    assertThatThrownBy(cache()::getCredentials)
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessage("Barclays Bedrock gateway authentication failed: the Barclays token source failed.")
        .hasNoCause()
        .extracting(e -> ((BarclaysAuthenticationException) e).reason())
        .isEqualTo(AuthenticationFailureReason.TOKEN_SOURCE_FAILED);
  }

  @Test
  void nullTokenIsAFailureNotAnUnauthenticatedRequest() {
    when(tokenClient.requestToken()).thenReturn(null);

    assertThatThrownBy(cache()::getCredentials)
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessageContaining("no token returned");
  }

  @Test
  void additionalHeadersKeepTheirOrderAndTokenHeaderComesLast() {
    countingTokens(Duration.ofHours(1), Duration.ZERO);
    final var ordered = new java.util.LinkedHashMap<String, String>();
    ordered.put("Accept", "application/json");
    ordered.put("Host", "gw.internal");
    final var cache =
        new BamTokenCache(
            tokenClient, "x-bam-token", "{token}", ordered, Duration.ofSeconds(60),
            Duration.ofSeconds(1), clock);

    assertThat(cache.getCredentials().headers().keySet())
        .containsExactly("Accept", "Host", "x-bam-token");
    assertThat(cache.getCredentials().headers()).containsEntry("x-bam-token", "tok-1");
  }

  @Test
  void closeClosesTheTokenClient() {
    cache().close();

    verify(tokenClient, times(1)).close();
  }

  @Test
  void rejectsTemplateWithoutPlaceholder() {
    assertThatThrownBy(
            () ->
                new BamTokenCache(
                    tokenClient, "Authorization", "Bearer", Map.of(), Duration.ZERO,
                    Duration.ofSeconds(1), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
