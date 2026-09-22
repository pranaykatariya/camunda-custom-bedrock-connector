package com.anthrobyte.camunda.aiagent.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A thread-safe clock that tests can move forward. */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  public static MutableClock startingNow() {
    return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  }

  public void advance(Duration duration) {
    now.updateAndGet(i -> i.plus(duration));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
