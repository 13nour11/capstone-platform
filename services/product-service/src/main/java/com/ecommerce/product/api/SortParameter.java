package com.ecommerce.product.api;

import java.util.Set;

import org.springframework.data.domain.Sort;

/** Parses {@code sort=<property>[,asc|desc]} against a whitelist, so clients cannot sort by arbitrary columns. */
final class SortParameter {

    static final Set<String> SORTABLE = Set.of("id", "name", "price");

    private SortParameter() {
    }

    static Sort parse(String value) {
        String[] parts = value.split(",", 2);
        String property = parts[0].strip();
        if (!SORTABLE.contains(property)) {
            throw new InvalidSortException(value);
        }
        Sort.Direction direction = parts.length == 1
                ? Sort.Direction.ASC
                : Sort.Direction.fromOptionalString(parts[1].strip())
                        .orElseThrow(() -> new InvalidSortException(value));
        return Sort.by(direction, property);
    }

    static class InvalidSortException extends RuntimeException {

        InvalidSortException(String value) {
            super("Unsupported sort '" + value + "'; use one of " + SORTABLE + " with optional ,asc or ,desc");
        }
    }
}
