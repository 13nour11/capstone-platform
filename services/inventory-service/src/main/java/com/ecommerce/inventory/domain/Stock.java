package com.ecommerce.inventory.domain;

import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Version
    @Column(name = "version")
    private Long version;

    /** B4: true while a LowStock alert is outstanding for this product (deduplication per product). */
    @Column(name = "low_stock_alerted", nullable = false)
    private boolean lowStockAlerted;

    public Stock() {
    }

    public Stock(Long productId, int available, int reserved, Long version) {
        this.productId = productId;
        this.available = available;
        this.reserved = reserved;
        this.version = version;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public boolean isLowStockAlerted() {
        return lowStockAlerted;
    }

    /**
     * B4: decides whether this stock level raises a LowStock alert. Only a NORMAL to LOW transition does;
     * going back to the threshold or above re-arms the alert.
     *
     * @return true exactly when a new alert must be published
     */
    public boolean evaluateLowStock(int threshold) {
        if (available < threshold) {
            if (lowStockAlerted) {
                return false;
            }
            lowStockAlerted = true;
            return true;
        }
        lowStockAlerted = false;
        return false;
    }

    public boolean hasAvailable(int quantity) {
        return this.available >= quantity;
    }

    public void reserve(int quantity) {
        if (!hasAvailable(quantity)) {
            throw new InsufficientStockException(productId, quantity, available);
        }
        this.available -= quantity;
        this.reserved += quantity;
    }

    public void release(int quantity) {
        int releaseQty = Math.min(quantity, this.reserved);
        this.reserved -= releaseQty;
        this.available += releaseQty;
    }

    public void adjustAvailable(int newAvailable) {
        if (newAvailable < 0) {
            throw new IllegalArgumentException("Available quantity cannot be negative");
        }
        this.available = newAvailable;
    }

    public static class Builder {
        private Long productId;
        private int available;
        private int reserved;
        private Long version;

        public Builder productId(Long productId) {
            this.productId = productId;
            return this;
        }

        public Builder available(int available) {
            this.available = available;
            return this;
        }

        public Builder reserved(int reserved) {
            this.reserved = reserved;
            return this;
        }

        public Builder version(Long version) {
            this.version = version;
            return this;
        }

        public Stock build() {
            return new Stock(productId, available, reserved, version);
        }
    }
}
