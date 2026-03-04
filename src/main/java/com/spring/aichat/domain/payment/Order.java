package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter @NoArgsConstructor
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_merchant_uid", columnList = "merchant_uid", unique = true),
    @Index(name = "idx_order_user_id", columnList = "user_id"),
    @Index(name = "idx_order_status", columnList = "status")
})
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_uid", nullable = false, unique = true, length = 50)
    private String merchantUid;

    @Column(name = "imp_uid", length = 50)
    private String impUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductType productType;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "target_character_id")
    private Long targetCharacterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failed_reason", length = 200)
    private String failedReason;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public static Order create(String merchantUid, User user, ProductType productType, Long targetCharacterId) {
        Order o = new Order();
        o.merchantUid = merchantUid;
        o.user = user;
        o.productType = productType;
        o.amount = productType.getPriceKrw();
        o.status = OrderStatus.PENDING;
        o.targetCharacterId = targetCharacterId;
        return o;
    }

    public void markPaid(String impUid) {
        if (this.status != OrderStatus.PENDING) throw new IllegalStateException("Only PENDING can be PAID. Current: " + this.status);
        this.impUid = impUid;
        this.status = OrderStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = OrderStatus.FAILED;
        this.failedReason = reason;
    }

    public void markExpired() {
        if (this.status == OrderStatus.PENDING) this.status = OrderStatus.EXPIRED;
    }

    public void markRefunded() {
        if (this.status != OrderStatus.PAID) throw new IllegalStateException("Only PAID can be REFUNDED. Current: " + this.status);
        this.status = OrderStatus.REFUNDED;
    }
}