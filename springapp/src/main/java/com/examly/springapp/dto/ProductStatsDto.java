package com.examly.springapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 13B (completion) — aggregate inventory figures for the admin product list.
 * The list itself is paginated, so its KPI cards cannot be derived from the current
 * page; these numbers are computed over the whole table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductStatsDto {

    private long totalProducts;
    private long inStock;
    private long outOfStock;
    private long lowStock;
    private double totalValue;
}
