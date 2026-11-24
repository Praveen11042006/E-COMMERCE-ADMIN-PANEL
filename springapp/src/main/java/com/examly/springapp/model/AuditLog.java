package com.examly.springapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Audit trail for important administrative actions.
 * NEVER stores passwords, tokens or secrets.
 */
@Entity
// Phase 9: index for the audit feed, which always sorts by timestamp descending.
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;

    /** Email of the authenticated actor (or "anonymous"). */
    private String actor;

    /** e.g. PRODUCT_CREATE, ORDER_STATUS_UPDATE, USER_DELETE, LOGIN. */
    private String action;

    /** Entity type, e.g. Product, Order, User. */
    private String entity;

    private String entityId;

    /** SUCCESS or FAILURE. */
    private String result;

    @Column(length = 1000)
    private String details;
}
