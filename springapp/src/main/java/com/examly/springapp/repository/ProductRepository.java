package com.examly.springapp.repository;

import com.examly.springapp.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCategoryContainingIgnoreCase(String category);
    List<Product> findByPriceBetween(double min, double max);
    List<Product> findByPriceGreaterThanEqual(double min);
    List<Product> findByPriceLessThanEqual(double max);
    List<Product> findByCategoryContainingIgnoreCaseAndPriceBetween(String category, double min, double max);
    List<Product> findByCategoryContainingIgnoreCaseAndPriceGreaterThanEqual(String category, double min);
    List<Product> findByCategoryContainingIgnoreCaseAndPriceLessThanEqual(String category, double max);

    /**
     * Phase 13B: paged catalogue query with the same filters as the legacy array
     * endpoint plus free-text search. Null parameters mean "no filter", so a single
     * query serves every filter combination and the database does the paging.
     */
    @Query("select p from Product p "
            + "where (:category is null or lower(p.category) like lower(concat('%', :category, '%'))) "
            + "and (:search is null or lower(p.name) like lower(concat('%', :search, '%')) "
            + "     or lower(p.description) like lower(concat('%', :search, '%'))) "
            + "and (:minPrice is null or p.price >= :minPrice) "
            + "and (:maxPrice is null or p.price <= :maxPrice)")
    Page<Product> searchPaged(@Param("category") String category,
                              @Param("search") String search,
                              @Param("minPrice") Double minPrice,
                              @Param("maxPrice") Double maxPrice,
                              Pageable pageable);

    /** Inventory aggregates for the admin KPI cards (no rows loaded). */
    long countByStockQuantity(int stockQuantity);

    long countByStockQuantityBetween(int minInclusive, int maxInclusive);

    @Query("select coalesce(sum(p.price * p.stockQuantity), 0) from Product p")
    double sumInventoryValue();

    /** Products at or below the given stock threshold (used for low-stock analytics). */
    List<Product> findByStockQuantityLessThanEqualOrderByStockQuantityAsc(int threshold);

    /** Lowest-stocked products below a threshold, limited by the pageable (analytics widget). */
    List<Product> findByStockQuantityLessThanOrderByStockQuantityAsc(int threshold, Pageable pageable);

    /** Locks products in a deterministic order for multi-item checkout stock updates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdInForUpdate(@Param("ids") List<Long> ids);
}
