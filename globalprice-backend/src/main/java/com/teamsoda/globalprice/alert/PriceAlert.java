package com.teamsoda.globalprice.alert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "price_alerts")
public class PriceAlert {

    public enum Basis { TARGET_PRICE, PERIOD_LOWEST, EVENT_START, COUPON_EXPIRY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "store_event_id")
    private Long storeEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_basis", nullable = false)
    private Basis basis;

    @Column(name = "target_price_krw")
    private BigDecimal targetPriceKrw;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PriceAlert() {
    }

    public PriceAlert(Long userId, Basis basis, Long productId, Long storeEventId, BigDecimal targetPriceKrw,
                      LocalDateTime createdAt) {
        this.userId = userId;
        this.basis = basis;
        this.productId = productId;
        this.storeEventId = storeEventId;
        this.targetPriceKrw = targetPriceKrw;
        this.createdAt = createdAt;
    }

    public void update(BigDecimal targetPriceKrw) {
        this.targetPriceKrw = targetPriceKrw;
        this.active = true;
    }

    public void markNotified(LocalDateTime at) {
        this.lastNotifiedAt = at;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getProductId() { return productId; }
    public Long getStoreEventId() { return storeEventId; }
    public Basis getBasis() { return basis; }
    public BigDecimal getTargetPriceKrw() { return targetPriceKrw; }
    public boolean isActive() { return active; }
    public LocalDateTime getLastNotifiedAt() { return lastNotifiedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
