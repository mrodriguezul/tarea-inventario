package com.store.inventory;

import com.store.inventory.api.ProductCategory;

import java.time.Duration;
import java.util.OptionalInt;

final class CategoryPolicy {

    private CategoryPolicy() {
    }

    static Duration reservationDuration(ProductCategory category) {
        return switch (category) {
            case STANDARD -> Duration.ofMinutes(15);
            case PRE_ORDER -> Duration.ofHours(24);
            case FLASH_SALE -> Duration.ofMinutes(5);
        };
    }

    static OptionalInt maxUnitsPerOrder(ProductCategory category) {
        return switch (category) {
            case STANDARD, PRE_ORDER -> OptionalInt.empty();
            case FLASH_SALE -> OptionalInt.of(2);
        };
    }
}
