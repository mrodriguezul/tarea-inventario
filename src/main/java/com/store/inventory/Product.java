package com.store.inventory;

import com.store.inventory.api.ProductCategory;

final class Product {
    private final String sku;
    private final ProductCategory category;
    private int stock;
    private boolean lowStockAlertSent;

    public Product(String sku, ProductCategory category, int stock) {
        this.sku = sku;
        this.category = category;
        this.stock = stock;
    }

    public String getSku() {
        return sku;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public int getStock() {
        return stock;
    }

    public void addStock(int quantity) {
        this.stock += quantity;
        this.lowStockAlertSent = false;
    }

    public void removeStock(int quantity) {
        this.stock -= quantity;
    }

    public boolean isLowStockAlertSent() {
        return lowStockAlertSent;
    }

    public void markLowStockAlertSent() {
        this.lowStockAlertSent = true;
    }

}