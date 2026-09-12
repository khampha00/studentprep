package com.studentprep.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedResponse<T> extends ApiResponse<T> {

    public PagedResponse(T data, Meta meta) {
        super(data, meta);
    }

    public static <T> PagedResponse<T> of(T data, int page, int size, long totalElements, int totalPages) {
        return new PagedResponse<>(data, new Meta(page, size, totalElements, totalPages));
    }
}
