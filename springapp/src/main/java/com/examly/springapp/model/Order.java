package com.examly.springapp.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
// Phase 9: hot-column indexes (created by ddl-auto=update — additive only, no data touched).
// NOTE: when a migration tool (Flyway/Liquibase) is adopted, these DDL index additions
// should move into a versioned migration and ddl-auto should be reduced to "validate".
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_user_id", columnList = "user_id"),
        @Index(name = "idx_orders_customer_email", columnList = "customer_email"),
        @Index(name = "idx_orders_order_date", columnList = "order_date"),
        @Index(name = "idx_orders_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private String shippingAddress;

    private LocalDateTime orderDate;

    private String status;

    /**
     * FK to the User who placed this order.
     * Nullable to preserve backward compatibility with existing orders that have no userId.
     */
    @Column(name = "user_id")
    private Long userId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<OrderItem> orderItems;

    private double totalAmount;

    /** Optional coupon code applied to this order. */
    private String couponCode;

    /** Discount amount applied (>= 0). totalAmount is the final payable amount. */
    private double discountAmount;
}
