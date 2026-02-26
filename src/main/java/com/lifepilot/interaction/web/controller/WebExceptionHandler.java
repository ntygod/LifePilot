package com.lifepilot.interaction.web.controller;

import java.time.Instant;

import com.lifepilot.interaction.web.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，统一将异常转换为标准化 {@link ErrorResponse}。
 *
 * <p>4xx 级别异常记录 WARN 日志，5xx 级别异常记录 ERROR 日志。
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    /**
     * 处理参数校验异常，返回 400 Bad Request。
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("请求参数无效: {}", ex.getMessage());
        var error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 处理运行时异常，返回 500 Internal Server Error。
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        log.error("服务器内部错误: {}", ex.getMessage(), ex);
        var error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "服务器内部错误",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * 兜底处理所有未捕获异常，返回 500 Internal Server Error。
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("未预期的异常: {}", ex.getMessage(), ex);
        var error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "服务器内部错误",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
