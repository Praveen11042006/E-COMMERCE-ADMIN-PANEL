package com.examly.springapp.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Phase 10C — consistent server-side pagination envelope.
 *
 * <p>Used by the newly added paginated endpoints (admin orders, customer orders,
 * admin users). It intentionally exposes a clean, frontend-friendly shape
 * ({@code content/page/size/totalElements/totalPages/first/last}) instead of
 * Spring Data's verbose {@link Page} JSON (which also leaks {@code pageable},
 * {@code sort}, etc.). This is the single pagination format for the project.
 *
 * @param <T> element type of {@code content}
 */
public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    public PageResponse() {
    }

    public PageResponse(List<T> content, int page, int size,
                        long totalElements, int totalPages,
                        boolean first, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.first = first;
        this.last = last;
    }

    /** Maps a Spring Data {@link Page} into this envelope (element-for-element). */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /**
     * Builds an envelope from an already-materialised page of content.
     * Used by the two-step pagination (id page + fetch-join), where no Spring
     * Data {@link Page} instance exists but the same shape must be returned.
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (size <= 0) ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        boolean first = page <= 0;
        boolean last = totalPages == 0 || page >= totalPages - 1;
        return new PageResponse<>(content, page, size, totalElements, totalPages, first, last);
    }

    public List<T> getContent() {
        return content;
    }

    public void setContent(List<T> content) {
        this.content = content;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public boolean isFirst() {
        return first;
    }

    public void setFirst(boolean first) {
        this.first = first;
    }

    public boolean isLast() {
        return last;
    }

    public void setLast(boolean last) {
        this.last = last;
    }
}
