package com.lifepilot.llm.multimodal.gemini;

/**
 * Gemini File API 上传或轮询失败异常。
 *
 * @author zsg
 * @since 2026-03-18
 */
public class GeminiFileUploadException extends RuntimeException {

    public GeminiFileUploadException(String message) {
        super(message);
    }

    public GeminiFileUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
