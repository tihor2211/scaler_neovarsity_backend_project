package com.dashcart.common;

import java.io.Serializable;
import java.util.List;

import org.springframework.data.domain.Page;

// Stable JSON shape for paged results (Spring's Page has no guaranteed JSON format).
// Serializable because cached listings are stored in Redis.
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)
        implements Serializable {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(List.copyOf(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
