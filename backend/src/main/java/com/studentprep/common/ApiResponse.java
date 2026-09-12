package com.studentprep.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final T data;
    private final Meta meta;

    public ApiResponse(T data, Meta meta) {
        this.data = data;
        this.meta = meta;
    }

    public T getData() { return data; }
    public Meta getMeta() { return meta; }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> of(T data, Meta meta) {
        return new ApiResponse<>(data, meta);
    }

    public record Meta(int page, int size, long totalElements, int totalPages) {}
}
