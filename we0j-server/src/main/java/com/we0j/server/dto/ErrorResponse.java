package com.we0j.server.dto;

/** 统一错误体（DDD §8.1）：{@code {"error":{"code":...,"message":...,"details":...,"requestId":...}}}。 */
public record ErrorResponse(ErrorBody error) {

    public static ErrorResponse of(String code, String message, Object details, String requestId) {
        return new ErrorResponse(new ErrorBody(code, message, details, requestId));
    }

    public record ErrorBody(String code, String message, Object details, String requestId) {
    }
}
