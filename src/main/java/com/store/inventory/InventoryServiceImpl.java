package com.store.inventory;

import com.store.inventory.api.*;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class InventoryServiceImpl implements InventoryService {

    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final StockAlertListener alertListener;
    private final Clock clock;

    public InventoryServiceImpl(Clock clock, StockAlertListener alertListener) {
        this.alertListener = alertListener;
        this.clock = clock;
    }

    @Override
    public synchronized void registerProduct(String sku, ProductCategory category) {
        Product existing = products.get(sku);

        if (existing != null) {
            if (existing.getCategory() != category) {
                throw new IllegalArgumentException("Product already registered with another category: " + sku);
            }
            return;
        }
        products.put(sku, new Product(sku, category, 0));
    }

    @Override
    public synchronized void addStock(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        Product product = products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product is not registered: " + sku);
        }

        product.addStock(quantity);
    }

    @Override
    public synchronized Reservation reserve(String orderId, String sku, int quantity) {
        removeExpiredReservations();
        Reservation existingReservation = reservations.get(orderId);
        if (existingReservation != null) {
            return existingReservation;
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        Product product = products.get(sku);
        if (product != null) {
            var maxUnitsPerOrder = CategoryPolicy.maxUnitsPerOrder(product.getCategory());
            if (maxUnitsPerOrder.isPresent() && quantity > maxUnitsPerOrder.getAsInt()) {
                throw new OrderLimitExceededException(
                        sku, quantity, maxUnitsPerOrder.getAsInt());
            }
        }

        int availableUnits = available(sku);
        if (quantity > availableUnits) {
            throw new InsufficientStockException(sku, quantity, availableUnits);
        }

        Instant expiresAt = clock.instant().plus(CategoryPolicy.reservationDuration(product.getCategory()));
        Reservation reservation = new Reservation(orderId, sku, quantity, expiresAt);
        reservations.put(orderId, reservation);

        int currentAvailable = available(sku);
        if (currentAvailable <= 5 && !product.isLowStockAlertSent()) {
            product.markLowStockAlertSent();
            alertListener.onLowStock(sku, currentAvailable);
        }

        return reservation;
    }

    @Override
    public synchronized void confirm(String orderId) {
        removeExpiredReservations();
        Reservation reservation = reservations.remove(orderId);
        if (reservation == null) {
            throw new IllegalStateException("No active reservation for order: " + orderId);
        }

        Product product = products.get(reservation.sku());
        product.removeStock(reservation.quantity());
    }

    @Override
    public synchronized int available(String sku) {
        removeExpiredReservations();
        Product product = products.get(sku);
        if (product == null) {
            return 0;
        }

        int reservedUnits = reservations.values().stream()
                .filter(reservation -> reservation.sku().equals(sku))
                .mapToInt(Reservation::quantity)
                .sum();
        return product.getStock() - reservedUnits;
    }

    private void removeExpiredReservations() {
        Instant now = clock.instant();
        reservations.values().removeIf(reservation -> !reservation.expiresAt().isAfter(now));
    }
}
