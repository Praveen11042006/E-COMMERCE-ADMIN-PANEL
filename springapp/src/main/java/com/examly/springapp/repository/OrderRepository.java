package com.examly.springapp.repository;

import com.examly.springapp.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    /* --- Phase 13E: dashboard aggregates (no entities are loaded) --- */

    interface OrderTotalsRow {
        Double getRevenue();
        Long getOrders();
        Long getCustomers();
    }

    interface StatusCountRow {
        String getStatus();
        Long getCount();
    }

    interface ProductSalesRow {
        Long getProductId();
        String getName();
        String getCategory();
        Long getQuantity();
        Double getRevenue();
    }

    interface CustomerSalesRow {
        String getEmail();
        String getName();
        Double getTotalSpent();
        Long getTotalOrders();
        LocalDateTime getFirstOrder();
        LocalDateTime getLastOrder();
    }

    interface CustomerOrderCountRow {
        String getEmail();
        Long getTotalOrders();
    }

    interface RecentOrderRow {
        Long getId();
        String getCustomerName();
        String getCustomerEmail();
        LocalDateTime getOrderDate();
        String getStatus();
        Double getTotalAmount();
        Long getItemCount();
    }

    interface TimeSeriesRow {
        LocalDateTime getOrderDate();
        Double getTotalAmount();
        String getCustomerEmail();
    }

    @Query("select coalesce(sum(o.totalAmount), 0) as revenue, count(o) as orders, " +
           "count(distinct o.customerEmail) as customers " +
           "from Order o where o.orderDate >= :from and o.orderDate <= :to")
    OrderTotalsRow getOrderTotals(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Query("select upper(coalesce(o.status, 'UNKNOWN')) as status, count(o) as count " +
           "from Order o where o.orderDate >= :from and o.orderDate <= :to " +
           "group by upper(coalesce(o.status, 'UNKNOWN'))")
    List<StatusCountRow> getStatusCounts(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    @Query("select p.id as productId, p.name as name, p.category as category, " +
           "coalesce(sum(oi.quantity), 0) as quantity, " +
           "coalesce(sum(oi.priceAtPurchase * oi.quantity), 0) as revenue " +
           "from OrderItem oi join oi.product p join oi.order o " +
           "where o.orderDate >= :from and o.orderDate <= :to " +
           "group by p.id, p.name, p.category " +
           "order by sum(oi.priceAtPurchase * oi.quantity) desc")
    List<ProductSalesRow> getTopProducts(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    @Query("select o.customerEmail as email, max(o.customerName) as name, " +
           "coalesce(sum(o.totalAmount), 0) as totalSpent, count(o) as totalOrders, " +
           "min(o.orderDate) as firstOrder, max(o.orderDate) as lastOrder " +
           "from Order o where o.orderDate >= :from and o.orderDate <= :to " +
           "and o.customerEmail is not null " +
           "group by o.customerEmail order by sum(o.totalAmount) desc")
    List<CustomerSalesRow> getTopCustomers(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           Pageable pageable);

    @Query("select o.customerEmail as email, count(o) as totalOrders " +
           "from Order o where o.orderDate >= :from and o.orderDate <= :to " +
           "and o.customerEmail is not null group by o.customerEmail")
    List<CustomerOrderCountRow> getCustomerOrderCounts(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    @Query("select o.id as id, o.customerName as customerName, o.customerEmail as customerEmail, " +
           "o.orderDate as orderDate, o.status as status, o.totalAmount as totalAmount, " +
           "count(oi) as itemCount " +
           "from Order o left join o.orderItems oi " +
           "where o.orderDate >= :from and o.orderDate <= :to " +
           "group by o.id, o.customerName, o.customerEmail, o.orderDate, o.status, o.totalAmount " +
           "order by o.orderDate desc")
    List<RecentOrderRow> getRecentOrders(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    @Query("select o.orderDate as orderDate, o.totalAmount as totalAmount, " +
           "o.customerEmail as customerEmail from Order o " +
           "where o.orderDate >= :from and o.orderDate <= :to order by o.orderDate")
    List<TimeSeriesRow> getTimeSeries(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /** Scalar KPI values; deliberately does not join order items/products. */
    interface OrderStatsAggregate {
        long getTotal();
        long getPending();
        long getProcessing();
        long getShipped();
        long getDelivered();
        long getCancelled();
        double getTotalRevenue();
        long getRevenueOrderCount();
    }

    @Query("select count(o) as total, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) = 'PENDING' then 1 else 0 end), 0) as pending, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) = 'PROCESSING' then 1 else 0 end), 0) as processing, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) = 'SHIPPED' then 1 else 0 end), 0) as shipped, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) = 'DELIVERED' then 1 else 0 end), 0) as delivered, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) = 'CANCELLED' then 1 else 0 end), 0) as cancelled, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) <> 'CANCELLED' then o.totalAmount else 0 end), 0) as totalRevenue, " +
           "coalesce(sum(case when upper(coalesce(o.status, '')) <> 'CANCELLED' then 1 else 0 end), 0) as revenueOrderCount " +
           "from Order o")
    OrderStatsAggregate getOrderStatsAggregate();

    /** Serializes payment attempts for a single order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    java.util.Optional<Order> findByIdForUpdate(@Param("id") Long id);

    List<Order> findByUserId(Long userId);
    List<Order> findByCustomerEmail(String email);

    /** Loads all orders with items + products eagerly to avoid N+1 in analytics. */
    @Query("select distinct o from Order o " +
           "left join fetch o.orderItems oi " +
           "left join fetch oi.product")
    List<Order> findAllWithItems();

    /**
     * Phase 10B: customer order-history fetch-join. Mirrors {@link #findAllWithItems()}
     * (same {@code distinct} + two {@code left join fetch}) but scoped to one owner,
     * so the customer order list loads orders + items + products in a SINGLE query
     * instead of one query per order/item (N+1). Returns identical data to the lazy
     * {@link #findByUserId(Long)} — only the number of SQL statements changes.
     */
    @Query("select distinct o from Order o " +
           "left join fetch o.orderItems oi " +
           "left join fetch oi.product " +
           "where o.userId = :userId")
    List<Order> findByUserIdWithItems(@Param("userId") Long userId);

    /**
     * Phase 10C, step 1 of 2: fetch ONLY the ids of the requested page.
     *
     * <p>Ids (not whole rows) are selected on purpose: a collection fetch join
     * inside a paged query is applied in memory by Hibernate, which silently
     * breaks LIMIT/OFFSET. Selecting ids here keeps the paging strictly
     * database-side; step 2 then loads the rows for those ids.
     *
     * <p>Filters are expressed with sentinel values instead of {@code :param IS NULL}
     * (Hibernate cannot always type a null parameter correctly):
     * {@code userId=-1} (no owner scope), {@code status=""} (no status filter),
     * {@code searchText="%"} (no text filter), {@code searchId=-1} (no id filter),
     * {@code dateFrom=MIN}/{@code dateTo=MAX}, {@code minAmount=-MAX_VALUE}/
     * {@code maxAmount=MAX_VALUE}.
     */
    @Query(value = "select o.id from Order o where " +
            "(:userId = -1 or o.userId = :userId) and " +
            "(:status = '' or upper(o.status) = :status) and " +
            "(:searchText = '%' or lower(o.customerName) like :searchText escape '!' " +
            " or lower(o.customerEmail) like :searchText escape '!' " +
            " or lower(o.shippingAddress) like :searchText escape '!' " +
            " or o.id = :searchId) and " +
            "(:dateFrom = :dateMin or o.orderDate >= :dateFrom) and " +
            "(:dateTo = :dateMax or o.orderDate <= :dateTo) and " +
            "(:minAmount = :amountMin or o.totalAmount >= :minAmount) and " +
            "(:maxAmount = :amountMax or o.totalAmount <= :maxAmount)",
            countQuery = "select count(o.id) from Order o where " +
            "(:userId = -1 or o.userId = :userId) and " +
            "(:status = '' or upper(o.status) = :status) and " +
            "(:searchText = '%' or lower(o.customerName) like :searchText escape '!' " +
            " or lower(o.customerEmail) like :searchText escape '!' " +
            " or lower(o.shippingAddress) like :searchText escape '!' " +
            " or o.id = :searchId) and " +
            "(:dateFrom = :dateMin or o.orderDate >= :dateFrom) and " +
            "(:dateTo = :dateMax or o.orderDate <= :dateTo) and " +
            "(:minAmount = :amountMin or o.totalAmount >= :minAmount) and " +
            "(:maxAmount = :amountMax or o.totalAmount <= :maxAmount)")
    Page<Long> findIdsPaged(@Param("userId") Long userId,
                             @Param("status") String status,
                             @Param("searchText") String searchText,
                             @Param("searchId") Long searchId,
                             @Param("dateFrom") LocalDateTime dateFrom,
                             @Param("dateTo") LocalDateTime dateTo,
                             @Param("dateMin") LocalDateTime dateMin,
                             @Param("dateMax") LocalDateTime dateMax,
                             @Param("minAmount") Double minAmount,
                             @Param("maxAmount") Double maxAmount,
                             @Param("amountMin") Double amountMin,
                             @Param("amountMax") Double amountMax,
                             Pageable pageable);

    /**
     * Phase 10C, step 2 of 2: load the given orders together with their items
     * and products in a SINGLE query (no N+1), then the service restores the
     * id order of the page.
     */
    @Query("select distinct o from Order o " +
           "left join fetch o.orderItems oi " +
           "left join fetch oi.product " +
           "where o.id in :ids")
    List<Order> findAllByIdInWithItems(@Param("ids") List<Long> ids);
}

