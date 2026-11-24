package com.examly.springapp.dto;

import lombok.*;

import jakarta.validation.ValidationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 10C — shared parsing/validation of the list query parameters.
 *
 * <p>Built from raw {@code @RequestParam} values so both the order and user
 * paginated endpoints accept the same parameter names. Every value is
 * sanitised here:
 * <ul>
 *   <li>{@code page} is clamped to >= 0, {@code size} to 1..{@value #MAX_SIZE}.</li>
 *   <li>{@code sort} is checked against an explicit whitelist of sortable
 *       columns — an unknown column falls back to the default instead of
 *       reaching the query as a raw identifier.</li>
 *   <li>Blank text/date/number parameters are treated as "not provided".</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageRequestParams {

    /** Maximum page size a client may request (protects the server). */
    public static final int MAX_SIZE = 100;

    /** Default page size used when the client does not specify one. */
    public static final int DEFAULT_SIZE = 10;

    /** Sortable order columns (maps to entity property names). */
    private static final Set<String> ORDER_SORT_FIELDS =
            Set.of("id", "orderDate", "totalAmount", "customerName", "status");

    /** Sortable user columns. */
    private static final Set<String> USER_SORT_FIELDS =
            Set.of("id", "name", "email", "role", "createdAt");

    /** Sortable product columns (Phase 13B). */
    private static final Set<String> PRODUCT_SORT_FIELDS =
            Set.of("id", "name", "price", "category", "stockQuantity");

    private static final Set<String> ORDER_STATUSES =
            Set.of("PENDING", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED");

    private int page;
    private int size;

    /** Validated sort property (always one of the whitelisted fields). */
    private String sortBy;

    /** {@code true} when sorting ascending. */
    private boolean sortAscending;

    /** Optional exact status filter (upper-cased); {@code null} = all. */
    private String status;

    /** Optional free-text search term (customer name / email / address / id). */
    private String search;

    /** Optional inclusive lower bound on the order date. */
    private LocalDateTime dateFrom;

    /** Optional inclusive upper bound on the order date. */
    private LocalDateTime dateTo;

    /** Optional inclusive lower bound on the order total. */
    private Double minAmount;

    /** Optional inclusive upper bound on the order total. */
    private Double maxAmount;

    /** Optional role filter for the user list; {@code null} = all. */
    private String role;

    /**
     * Parses the raw request parameters into a validated set of parameters.
     *
     * @param page         raw page number (0-based); {@code null}/negative -> 0
     * @param size         raw page size; {@code null}/non-positive -> default
     * @param sort         raw sort expression ({@code field} or {@code field,dir})
     * @param sortFields   the whitelist of sortable columns for this resource
     * @param defaultSort  the sort column used when nothing valid was supplied
     * @param defaultAsc   the sort direction used when nothing valid was supplied
     */
    public static PageRequestParams parse(Integer page, Integer size, String sort,
                                           Set<String> sortFields, String defaultSort,
                                           boolean defaultAsc) {
        PageRequestParams params = new PageRequestParams();

        if (page != null && page < 0) {
            throw new ValidationException("page must be zero or greater");
        }
        if (size != null && (size <= 0 || size > MAX_SIZE)) {
            throw new ValidationException("size must be between 1 and " + MAX_SIZE);
        }
        params.setPage(page == null ? 0 : page);
        params.setSize(size == null ? DEFAULT_SIZE : size);

        String[] parts = (sort == null || sort.isBlank()) ? new String[0] : sort.split(",");
        if (parts.length > 2) {
            throw new ValidationException("sort must use field,asc or field,desc");
        }
        String rawField = parts.length > 0 ? parts[0].trim() : "";
        if (!rawField.isEmpty() && !sortFields.contains(rawField)) {
            throw new ValidationException("Unsupported sort field: " + rawField);
        }
        String field = rawField.isEmpty() ? defaultSort : rawField;
        boolean ascending = defaultAsc;
        if (parts.length > 1) {
            String dir = parts[1].trim().toLowerCase(Locale.ROOT);
            if ("asc".equals(dir)) {
                ascending = true;
            } else if ("desc".equals(dir)) {
                ascending = false;
            } else {
                throw new ValidationException("sort direction must be asc or desc");
            }
        }
        params.setSortBy(field);
        params.setSortAscending(ascending);

        return params;
    }

    /** Builds the parameters for the order list (sort whitelist = orders). */
    public static PageRequestParams parseOrderParams(Integer page, Integer size, String sort) {
        return parse(page, size, sort, ORDER_SORT_FIELDS, "orderDate", false);
    }

    /** Builds the parameters for the user list (sort whitelist = users). */
    public static PageRequestParams parseUserParams(Integer page, Integer size, String sort) {
        return parse(page, size, sort, USER_SORT_FIELDS, "name", true);
    }

    /** Builds the parameters for the product catalogue (sort whitelist = products). */
    public static PageRequestParams parseProductParams(Integer page, Integer size, String sort) {
        return parse(page, size, sort, PRODUCT_SORT_FIELDS, "name", true);
    }

    /** Normalises the status filter: blank -> {@code null}, otherwise upper-cased. */
    public PageRequestParams withStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank() || "ALL".equalsIgnoreCase(rawStatus.trim())) {
            this.status = null;
        } else {
            this.status = rawStatus.trim().toUpperCase(Locale.ROOT);
            if (!ORDER_STATUSES.contains(this.status)) {
                throw new ValidationException("Invalid order status: " + rawStatus);
            }
        }
        return this;
    }

    /** Normalises the role filter: blank -> {@code null}, otherwise upper-cased. */
    public PageRequestParams withRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank() || "ALL".equalsIgnoreCase(rawRole.trim())) {
            this.role = null;
        } else {
            this.role = rawRole.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("USER", "ADMIN").contains(this.role)) {
                throw new ValidationException("Invalid user role: " + rawRole);
            }
        }
        return this;
    }

    /** Normalises the search term: blank -> {@code null}, otherwise trimmed. */
    public PageRequestParams withSearch(String rawSearch) {
        this.search = (rawSearch == null || rawSearch.isBlank()) ? null : rawSearch.trim();
        return this;
    }

    /** Parses an optional inclusive date bound ({@code yyyy-MM-dd}); invalid -> {@code null}. */
    public PageRequestParams withDateFrom(String rawDate) {
        this.dateFrom = parseDate(rawDate, false);
        validateRanges();
        return this;
    }

    /** Parses an optional inclusive date bound ({@code yyyy-MM-dd}); invalid -> {@code null}. */
    public PageRequestParams withDateTo(String rawDate) {
        this.dateTo = parseDate(rawDate, true);
        validateRanges();
        return this;
    }

    /** Parses an optional inclusive lower amount bound; invalid -> {@code null}. */
    public PageRequestParams withMinAmount(Double rawMin) {
        if (rawMin != null && rawMin < 0) {
            throw new ValidationException("minAmount must not be negative");
        }
        this.minAmount = rawMin;
        validateRanges();
        return this;
    }

    /** Parses an optional inclusive upper amount bound; invalid -> {@code null}. */
    public PageRequestParams withMaxAmount(Double rawMax) {
        if (rawMax != null && rawMax < 0) {
            throw new ValidationException("maxAmount must not be negative");
        }
        this.maxAmount = rawMax;
        validateRanges();
        return this;
    }

    /** {@code true} when the status filter matches one of the known statuses. */
    public boolean hasStatusFilter() {
        return status != null && ORDER_STATUSES.contains(status);
    }

    /** {@code true} when any filter other than paging/sorting is active. */
    public boolean hasFilters() {
        return hasStatusFilter() || search != null || dateFrom != null || dateTo != null
                || minAmount != null || maxAmount != null;
    }

    private LocalDateTime parseDate(String rawDate, boolean endOfDay) {
        if (rawDate == null || rawDate.isBlank()) return null;
        try {
            // A bare date covers the whole day.
            if (rawDate.length() <= 10) {
                LocalDate date = LocalDate.parse(rawDate.trim());
                return endOfDay ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
            }
            return LocalDateTime.parse(rawDate.trim().replace(' ', 'T'));
        } catch (Exception e) {
            throw new ValidationException("Invalid date: " + rawDate);
        }
    }

    private void validateRanges() {
        if (minAmount != null && minAmount < 0) {
            throw new ValidationException("minAmount must not be negative");
        }
        if (minAmount != null && maxAmount != null && minAmount > maxAmount) {
            throw new ValidationException("minAmount must not be greater than maxAmount");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new ValidationException("dateFrom must not be after dateTo");
        }
    }
}
