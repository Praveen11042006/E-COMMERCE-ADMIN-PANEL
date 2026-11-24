package com.examly.springapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Payment record. Uses a MOCK payment gateway (no real financial credentials).
 * Status transitions: PENDING -> SUCCESS | FAILED.
 */
@Entity
// Phase 9: index for payment lookups by order (findByOrderId runs on every payment + invoice request).
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_order_id", columnList = "order_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private double amount;

    /** MOCK for development. */
    private String method;

    /** PENDING | SUCCESS | FAILED */
    private String status;

    private String transactionRef;

    private String invoiceNumber;

    private LocalDateTime createdAt;
}
