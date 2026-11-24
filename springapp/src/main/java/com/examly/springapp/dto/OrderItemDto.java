package com.examly.springapp.dto;

import lombok.*;

/**
 * Phase 10C — one order line as shown in list views.
 *
 * <p>A flat projection of {@code OrderItem}: it carries only what the list UI
 * renders (product name/category, quantity, unit price and the line subtotal).
 * The owning order is NOT serialized (it would create a back-reference cycle)
 * and the full product entity is not embedded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDto {

    private Long id;

    /** Product id (kept so the UI can link to the product). */
    private Long productId;

    /** Product name at the time the order was placed. */
    private String productName;

    /** Product category (used by the admin list grouping/labels). */
    private String category;

    private int quantity;

    /** Unit price captured when the order was created. */
    private double priceAtPurchase;

    /** {@code priceAtPurchase * quantity}. */
    private double subtotal;
}
