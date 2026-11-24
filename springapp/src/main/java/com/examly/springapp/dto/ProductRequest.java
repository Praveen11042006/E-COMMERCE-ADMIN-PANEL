package com.examly.springapp.dto;

import com.examly.springapp.model.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 13B — explicit, validated request contract for product create/update.
 *
 * <p>Responses still serialize the {@link Product} entity, so the existing
 * response shape is unchanged. Only the accepted request body is constrained:
 * previously an entity was bound directly, which let negative prices, negative
 * stock and blank names reach the database through the update endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductRequest {

    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Product description is required")
    @Size(max = 255, message = "Product description must not exceed 255 characters")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer and 2 decimal digits")
    private Double price;

    @NotBlank(message = "Category is required")
    @Size(max = 100, message = "Category must not exceed 100 characters")
    private String category;

    @NotNull(message = "Stock quantity is required")
    @PositiveOrZero(message = "Stock quantity must not be negative")
    @Max(value = 1_000_000, message = "Stock quantity must not exceed 1000000")
    private Integer stockQuantity;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;

    /** Builds the entity the service persists (nulls are impossible after validation). */
    public Product toProduct() {
        return Product.builder()
                .name(trim(name))
                .description(trim(description))
                .price(price == null ? 0d : price)
                .category(trim(category))
                .stockQuantity(stockQuantity == null ? 0 : stockQuantity)
                .imageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim())
                .build();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
