package com.examly.springapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 13D — the latest stored payment of an order, projected for order lists.
 *
 * <p>Only real persisted {@code Payment} fields are exposed; the client never
 * computes or supplies payment state. {@code status} is one of PENDING,
 * SUCCESS or FAILED, and the whole object is {@code null} when the order has
 * no payment record yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSummaryDto {

    private String status;

    private String method;

    private double amount;

    private String transactionRef;

    private String invoiceNumber;

    private LocalDateTime createdAt;
}
