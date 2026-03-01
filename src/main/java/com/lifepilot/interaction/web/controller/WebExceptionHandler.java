package com.lifepilot.interaction.web.controller;

import java.time.Instant;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
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
     * 处理找不到处理器异常，返回 404 Not Found。
     *
     * <p>当请求的路径没有对应的控制器时，Spring MVC 会抛出此异常。
     * 这通常发生在控制器未注册或路径不匹配的情况下。</p>
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("找不到处理器: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        var error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "API 端点不存在: " + ex.getRequestURL(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 处理找不到静态资源异常，返回 404 Not Found。
     *
     * <p>当请求的路径没有对应的控制器时，Spring MVC 可能会尝试将其作为静态资源处理，
     * 如果静态资源也不存在，会抛出此异常。这通常发生在控制器未注册的情况下
     * （例如，由于依赖 Bean 不存在导致控制器未被注册）。</p>
     *
     * @param ex 异常
     * @return 标准化错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("找不到资源: {} {}", ex.getHttpMethod(), ex.getResourcePath());
        var error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "API 端点不存在: " + ex.getResourcePath(),
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
