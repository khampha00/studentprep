package com.studentprep.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for safe case-insensitive LIKE pattern escaping and
 * allowlisted column sorting across paginated admin table endpoints.
 */
public final class TableQueryUtils {

    private TableQueryUtils() {
    }

    /**
     * Trims raw search input and returns an escaped {@code %term%} pattern
     * compatible with JPQL {@code ESCAPE '!'}, or {@code null} when blank.
     */
    public static String toContainsPattern(String rawSearch) {
        if (rawSearch == null) {
            return null;
        }
        String trimmed = rawSearch.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String escaped = trimmed
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /**
     * Resolves an allowlisted {@link Pageable} for a given {@code sortBy} /
     * {@code sortDir} pair, falling back to {@code defaultProperty} and
     * {@code defaultDirection} when omitted or unrecognized.
     */
    public static Pageable buildPageable(
            int page,
            int size,
            String sortBy,
            String sortDir,
            Map<String, String> allowedProperties,
            String defaultProperty,
            Sort.Direction defaultDirection,
            String tieBreakerProperty) {
        String property = (sortBy != null && allowedProperties.containsKey(sortBy))
                ? allowedProperties.get(sortBy)
                : defaultProperty;

        Sort.Direction direction = defaultDirection;
        if ("asc".equalsIgnoreCase(sortDir)) {
            direction = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(sortDir)) {
            direction = Sort.Direction.DESC;
        }

        Sort sort = Sort.by(direction, property);
        if (tieBreakerProperty != null && !tieBreakerProperty.equals(property)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, tieBreakerProperty));
        }
        return PageRequest.of(page, size, sort);
    }
}
