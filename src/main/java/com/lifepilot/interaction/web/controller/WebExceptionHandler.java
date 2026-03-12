package com.lifepilot.interaction.web.controller;

import java.io.IOException;
import java.time.Instant;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 处理知识库不存在异常，返回 404 Not Found。
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleKbNotFound(KnowledgeBaseNotFoundException ex) {
        log.warn("知识库不存在: {}", ex.getMessage());
        var error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 处理文档不存在异常，返回 404 Not Found。
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocNotFound(DocumentNotFoundException ex) {
        log.warn("文档不存在: {}", ex.getMessage());
        var error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

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
     * 处理找不到资源/路由的情况，返回 404 Not Found。
     *
     * <p>Spring Boot 3.x 在静态资源链路下未命中时会抛出 {@link NoResourceFoundException}。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("未找到资源: {}", ex.getMessage());
        var error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 处理 SSE 连接断开导致的 IOException。
     *
     * <p>SSE 心跳或事件推送时客户端已断开连接，会抛出 IOException。
     * 此时 Content-Type 已是 text/event-stream，无法返回 JSON ErrorResponse，
     * 直接记录 DEBUG 日志并返回 null（让 Spring 跳过响应写入）。</p>
     *
     * @param ex      异常
     * @param request 当前请求
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleIOException(IOException ex, HttpServletRequest request) {
        log.debug("连接 I/O 异常（客户端可能已断开）: uri={}, message={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * 处理运行时异常，返回 500 Internal Server Error。
     *
     * <p>如果当前请求的 Content-Type 已是 text/event-stream（SSE 上下文），
     * 不返回 JSON ErrorResponse 以避免序列化失败。</p>
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 标准化错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("SSE 上下文中发生运行时异常，跳过 JSON 响应: uri={}, message={}", request.getRequestURI(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
     * <p>如果当前请求的 Content-Type 已是 text/event-stream（SSE 上下文），
     * 不返回 JSON ErrorResponse 以避免序列化失败。</p>
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 标准化错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("SSE 上下文中发生未预期异常，跳过 JSON 响应: uri={}, message={}", request.getRequestURI(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.error("未预期的异常: {}", ex.getMessage(), ex);
        var error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "服务器内部错误",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * 判断当前请求是否处于 SSE 上下文（Content-Type 为 text/event-stream）。
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        // 检查 Accept 头，SSE 请求通常 Accept: text/event-stream
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
