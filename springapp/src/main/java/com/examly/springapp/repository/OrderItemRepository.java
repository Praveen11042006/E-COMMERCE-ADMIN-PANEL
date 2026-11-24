package com.examly.springapp.repository;

import com.examly.springapp.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** One aggregated revenue row per product category. */
    interface CategorySalesRow {
        String getCategory();

        Double getRevenue();

        Long getQuantity();
    }

    /**
     * Phase 13D — revenue-by-category chart aggregation, computed by the database.
     *
     * <p>This is the exact calculation the dashboard previously did in JavaScript
     * over every order item: {@code priceAtPurchase * quantity} grouped by
     * product category, cancelled orders included (as before). Only aggregates
     * leave the database — no product/order rows are loaded.
     */
    @Query("select coalesce(p.category, 'Uncategorized') as category, "
            + "coalesce(sum(oi.priceAtPurchase * oi.quantity), 0) as revenue, "
            + "coalesce(sum(oi.quantity), 0) as quantity "
            + "from OrderItem oi "
            + "join oi.product p "
            + "join oi.order o "
            + "where o.orderDate >= :from and o.orderDate <= :to "
            + "group by coalesce(p.category, 'Uncategorized') "
            + "order by sum(oi.priceAtPurchase * oi.quantity) desc")
    List<CategorySalesRow> aggregateCategorySales(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);
}
