package com.examly.springapp.controller;

import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.dto.PageResponse;
import com.examly.springapp.dto.ProductRequest;
import com.examly.springapp.dto.ProductStatsDto;
import com.examly.springapp.model.Product;
import com.examly.springapp.service.AuditService;
import com.examly.springapp.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final AuditService auditService;

    public ProductController(ProductService productService, AuditService auditService) {
        this.productService = productService;
        this.auditService = auditService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Product> createProduct(@Valid @RequestBody ProductRequest request) {
        Product created = productService.createProduct(request.toProduct());
        auditService.logCurrentUser("PRODUCT_CREATE", "Product", created.getId(), "Created product: " + created.getName());
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice) {
        return ResponseEntity.ok(productService.getAllProducts(category, minPrice, maxPrice));
    }

    /**
     * Phase 13B: paged catalogue with the same filters as the array endpoint plus
     * free-text search. The array endpoint above is unchanged for existing clients.
     */
    @GetMapping("/paged")
    public ResponseEntity<PageResponse<Product>> getProductsPaged(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String search) {
        PageRequestParams params = PageRequestParams.parseProductParams(page, size, sort);
        return ResponseEntity.ok(productService.getProductsPage(category, minPrice, maxPrice, search, params));
    }

    /**
     * Phase 13B: whole-table inventory summary backing the paginated admin list
     * (the current page cannot produce these numbers).
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductStatsDto> getProductStats() {
        return ResponseEntity.ok(productService.getStats());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        Product updated = productService.updateProduct(id, request.toProduct());
        auditService.logCurrentUser("PRODUCT_UPDATE", "Product", id, "Updated product: " + updated.getName());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        auditService.logCurrentUser("PRODUCT_DELETE", "Product", id, "Deleted product");
        return ResponseEntity.noContent().build();
    }
}
