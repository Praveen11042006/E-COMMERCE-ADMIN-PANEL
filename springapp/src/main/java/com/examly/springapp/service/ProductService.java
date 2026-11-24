package com.examly.springapp.service;

import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.dto.PageResponse;
import com.examly.springapp.dto.ProductStatsDto;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.ProductRepository;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    /** Matches the low-stock threshold used by analytics and the admin list. */
    private static final int LOW_STOCK_THRESHOLD = 10;

    private final ProductRepository productRepository;

    public Product createProduct(Product product) {
        requireValidProduct(product);
        return productRepository.save(product);
    }

    public List<Product> getAllProducts(String category, Double minPrice, Double maxPrice) {
        boolean hasCategory = category != null && !category.isBlank();
        boolean hasMin = minPrice != null;
        boolean hasMax = maxPrice != null;

        if (hasCategory && hasMin && hasMax) {
            return productRepository.findByCategoryContainingIgnoreCaseAndPriceBetween(category, minPrice, maxPrice);
        } else if (hasCategory && hasMin) {
            return productRepository.findByCategoryContainingIgnoreCaseAndPriceGreaterThanEqual(category, minPrice);
        } else if (hasCategory && hasMax) {
            return productRepository.findByCategoryContainingIgnoreCaseAndPriceLessThanEqual(category, maxPrice);
        } else if (hasCategory) {
            return productRepository.findByCategoryContainingIgnoreCase(category);
        } else if (hasMin && hasMax) {
            return productRepository.findByPriceBetween(minPrice, maxPrice);
        } else if (hasMin) {
            return productRepository.findByPriceGreaterThanEqual(minPrice);
        } else if (hasMax) {
            return productRepository.findByPriceLessThanEqual(maxPrice);
        } else {
            return productRepository.findAll();
        }
    }

    /**
     * Phase 13B: one page of the catalogue, filtered and sorted in the database.
     * The legacy {@link #getAllProducts} array path is unchanged for existing clients.
     */
    public PageResponse<Product> getProductsPage(String category, Double minPrice, Double maxPrice,
                                                 String search, PageRequestParams params) {
        if (minPrice != null && minPrice < 0) {
            throw new ValidationException("minPrice must not be negative");
        }
        if (maxPrice != null && maxPrice < 0) {
            throw new ValidationException("maxPrice must not be negative");
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new ValidationException("minPrice must not be greater than maxPrice");
        }

        String categoryFilter = (category == null || category.isBlank()) ? null : category.trim();
        String searchFilter = (search == null || search.isBlank()) ? null : search.trim();

        Sort sort = Sort.by(params.isSortAscending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                params.getSortBy());
        Pageable pageable = PageRequest.of(params.getPage(), params.getSize(), sort);

        return PageResponse.from(
                productRepository.searchPaged(categoryFilter, searchFilter, minPrice, maxPrice, pageable));
    }

    /** Whole-table inventory summary for the paginated admin list. */
    public ProductStatsDto getStats() {
        long total = productRepository.count();
        long outOfStock = productRepository.countByStockQuantity(0);
        long lowStock = productRepository.countByStockQuantityBetween(1, LOW_STOCK_THRESHOLD);

        return ProductStatsDto.builder()
                .totalProducts(total)
                .outOfStock(outOfStock)
                .inStock(total - outOfStock)
                .lowStock(lowStock)
                .totalValue(productRepository.sumInventoryValue())
                .build();
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new RuntimeException("Product not found"));
    }

    public Product updateProduct(Long id, Product updated) {
        // Phase 13B: the update endpoint previously persisted whatever it was given,
        // including negative prices and stock. Request DTO validation runs first; this
        // check keeps the service safe for any other caller.
        requireValidProduct(updated);
        Product product = getProductById(id);
        product.setName(updated.getName());
        product.setDescription(updated.getDescription());
        product.setPrice(updated.getPrice());
        product.setCategory(updated.getCategory());
        product.setStockQuantity(updated.getStockQuantity());
        product.setImageUrl(updated.getImageUrl());
        return productRepository.save(product);
    }

    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    /** Shared business rules for create/update (validation DTO is the first gate). */
    private void requireValidProduct(Product product) {
        if (product.getName() == null || product.getName().isBlank() ||
            product.getDescription() == null || product.getDescription().isBlank() ||
            product.getPrice() <= 0 || product.getCategory() == null || product.getCategory().isBlank() ||
            product.getStockQuantity() < 0) {
            throw new ValidationException("Invalid product data");
        }
    }
}
