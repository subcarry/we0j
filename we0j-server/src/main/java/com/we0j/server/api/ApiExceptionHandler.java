package com.we0j.server.api;

import com.we0j.common.exception.ConfigValidationException;
import com.we0j.common.exception.ModelException;
import com.we0j.common.exception.NotFoundException;
import com.we0j.common.exception.SnapshotException;
import com.we0j.common.util.Ulids;
import com.we0j.server.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误体（DDD §8.1）：{@code {"error":{"code","message","details","requestId"}}}，
 * requestId 用 ULID 方便与日志关联。错误码 → HTTP 映射全表见 §8.1。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), e.details());
    }

    /** 领域 NotFoundException（SessionService.requireRow 等）→ 404；message 锚点缺失用扩展码。 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        String msg = e.getMessage() == null ? "not found" : e.getMessage();
        String code = msg.startsWith("message not found") ? "MESSAGE_NOT_FOUND" : "SESSION_NOT_FOUND";
        return body(HttpStatus.NOT_FOUND, code, msg, null);
    }

    @ExceptionHandler(ConfigValidationException.class)
    public ResponseEntity<ErrorResponse> config(ConfigValidationException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_CONFIG_LOCATION", e.getMessage(), null);
    }

    @ExceptionHandler(SnapshotException.class)
    public ResponseEntity<ErrorResponse> snapshot(SnapshotException e) {
        // rewind(BOTH) 目标无快照 → 冲突（前端引导改用 CONVERSATION）
        return body(HttpStatus.CONFLICT, "SNAPSHOT_UNAVAILABLE", e.getMessage(), null);
    }

    @ExceptionHandler(ModelException.class)
    public ResponseEntity<ErrorResponse> model(ModelException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", e.getMessage(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", rootMessage(e), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unhandled api failure", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                e.getClass().getSimpleName() + ": " + rootMessage(e), null);
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message,
                                                      Object details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, details, Ulids.next()));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? t.getClass().getSimpleName() : cur.getMessage();
    }
}
