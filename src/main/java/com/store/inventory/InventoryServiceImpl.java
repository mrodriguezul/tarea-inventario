package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;

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
    public void registerProduct(String sku, ProductCategory category) {
        products.put(sku, new Product(sku, category, 0));
    }

    @Override
    public void addStock(String sku, int quantity) {
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
    public Reservation reserve(String orderId, String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        int availableUnits = available(sku);
        if (quantity > availableUnits) {
            throw new InsufficientStockException(sku, quantity, availableUnits);
        }

        Reservation reservation = new Reservation(orderId, sku, quantity, Instant.MAX);
        reservations.put(orderId, reservation);
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        Reservation reservation = reservations.remove(orderId);
        if (reservation == null) {
            throw new IllegalStateException("No active reservation for order: " + orderId);
        }

        Product product = products.get(reservation.sku());
        product.removeStock(reservation.quantity());
    }

    @Override
    public int available(String sku) {
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
}
