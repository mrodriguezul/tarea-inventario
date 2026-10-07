package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {

    private InventoryService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(
                Instant.parse("2026-10-07T10:00:00Z")
        );
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void reservingReducesAvailableUnits() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        service.addStock("SKU-1", 2);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
    }

    @Test
    void confirmedUnitsStaySold() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void flashSaleShouldAllowMaximumTwoUnitsPerOrder() {
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("FLASH-1", 10);
        Reservation reservation = service.reserve("ORDER-1", "FLASH-1", 2);
        assertEquals(2, reservation.quantity());
    }

    @Test
    void flashSaleShouldRejectMoreThanTwoUnits() {
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("FLASH-1", 10);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH-1", 3));
    }

    @Test
    void standardReservationShouldExpireAfter15Minutes() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(7, service.available("SKU-1"));

        clock.advance(Duration.ofMinutes(14));
        assertEquals(7, service.available("SKU-1"));

        clock.advance(Duration.ofMinutes(2));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void flashSaleReservationShouldExpireAfter5Minutes() {
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("FLASH-1", 10);
        service.reserve("ORDER-1", "FLASH-1", 2);

        assertEquals(8, service.available("FLASH-1"));

        clock.advance(Duration.ofMinutes(4));
        assertEquals(8, service.available("FLASH-1"));

        clock.advance(Duration.ofMinutes(2));
        assertEquals(10, service.available("FLASH-1"));
    }

    @Test
    void reservationShouldBeExpiredExactlyAtExpirationTime() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("SKU-1"));
    }
}
