package com.store.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

final class MutableClock extends Clock {

    private Instant currentInstant;

    MutableClock(Instant initialInstant) {
        this.currentInstant = initialInstant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return currentInstant;
    }

    void advance(Duration duration) {
        currentInstant = currentInstant.plus(duration);
    }
}
