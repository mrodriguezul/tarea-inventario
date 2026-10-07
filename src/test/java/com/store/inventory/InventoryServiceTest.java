package com.store.inventory;

import com.store.inventory.api.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void retryingSameOrderShouldNotReserveStockTwice() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, retry);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retryingSameOrderShouldReturnOriginalReservation() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(5));

        Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, retry);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void shouldRejectZeroReservationQuantity() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0)
        );
    }

    @Test
    void shouldRejectNegativeReservationQuantity() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", -1));
    }

    @Test
    void shouldRejectZeroStockQuantity() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
    }

    @Test
    void shouldRejectNegativeStockQuantity() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -1));
    }

    @Test
    void shouldSendLowStockAlertWhenAvailableStockReachesFive() {
        List<String> alerts = new ArrayList<>();

        InventoryService service = Inventory.create(
                clock,
                (sku, available) -> alerts.add(sku + ":" + available)
        );

        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(1, alerts.size());
        assertEquals("SKU-1:5", alerts.get(0));
    }

    @Test
    void shouldNotRepeatLowStockAlertBeforeRestock() {
        List<String> alerts = new ArrayList<>();

        InventoryService service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));

        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 1);

        assertEquals(1, alerts.size());
    }

    @Test
    void shouldAllowNewLowStockAlertAfterRestock() {
        List<String> alerts = new ArrayList<>();

        InventoryService service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));

        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(1, alerts.size());

        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(2, alerts.size());
    }

    @Test
    void expirationShouldNotRearmLowStockAlert() {
        List<String> alerts = new ArrayList<>();

        InventoryService service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));

        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(1, alerts.size());

        clock.advance(Duration.ofMinutes(16));

        assertEquals(10, service.available("SKU-1"));

        service.reserve("ORDER-2", "SKU-1", 5);

        assertEquals(1, alerts.size());
    }

    @Test
    void concurrentReservationsShouldNeverOversell() throws Exception {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        int numberOfRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfRequests; i++) {
            String orderId = "ORDER-" + i;

            futures.add(executor.submit(() -> {
                startGate.await();
                try {
                    service.reserve(orderId, "SKU-1", 1);
                    return true;
                } catch (InsufficientStockException e) {
                    return false;
                }
            }));
        }
        startGate.countDown();

        int successfulReservations = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successfulReservations++;
            }
        }
        executor.shutdown();

        assertEquals(10, successfulReservations);
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void expiredOrderIdCanBeReservedAgain() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(16));

        Reservation second = service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(4, second.quantity());
        assertNotEquals(first.expiresAt(), second.expiresAt());
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void confirmingReservationShouldConsumePhysicalStock() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(7, service.available("SKU-1"));

        service.confirm("ORDER-1");

        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void confirmedReservationShouldNotRestoreStockAfterExpirationTime() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");
        clock.advance(Duration.ofMinutes(20));

        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void shouldNotConfirmExpiredReservation() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(16));

        assertThrows(IllegalStateException.class,() -> service.confirm("ORDER-1"));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void failedReservationShouldNotChangeAvailableStock() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 5);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 6));
        assertEquals(5, service.available("SKU-1"));
    }

    @Test
    void concurrentRetriesOfSameOrderShouldReserveOnlyOnce() throws Exception {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        int retries = 20;

        ExecutorService executor = Executors.newFixedThreadPool(10);

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Reservation>> futures = new ArrayList<>();
        for (int i = 0; i < retries; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return service.reserve("ORDER-1", "SKU-1", 3);
            }));
        }
        startGate.countDown();

        for (Future<Reservation> future : futures) {
            Reservation reservation = future.get();

            assertEquals("ORDER-1", reservation.orderId());
            assertEquals(3, reservation.quantity());
        }

        executor.shutdown();

        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void flashSaleWithinLimitButWithoutStockShouldThrowInsufficientStock() {
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("FLASH-1", 1);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "FLASH-1", 2));
        assertEquals(1, service.available("FLASH-1"));
    }

    @Test
    void preOrderReservationShouldExpireAfter24Hours() {
        service.registerProduct("PRE-1", ProductCategory.PRE_ORDER);
        service.addStock("PRE-1", 10);
        service.reserve("ORDER-1", "PRE-1", 3);

        assertEquals(7, service.available("PRE-1"));

        clock.advance(Duration.ofHours(23));

        assertEquals(7, service.available("PRE-1"));

        clock.advance(Duration.ofHours(2));

        assertEquals(10, service.available("PRE-1"));
    }

}
