package com.ecommerce.product.application;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Stable page shape for the API and the cache (Spring's PageImpl JSON is not a stable contract). */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResult<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResult<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
