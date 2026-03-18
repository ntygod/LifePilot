package com.lifepilot.llm.multimodal.gemini;

/**
 * Gemini File API 上传结果。
 *
 * @param fileUri   文件 URI（files/{fileId} 格式）
 * @param mimeType  MIME 类型
 * @param state     处理状态（PROCESSING / ACTIVE）
 * @param sizeBytes 文件大小（字节）
 * @author zsg
 * @since 2026-03-18
 */
public record GeminiFileUploadResult(
        String fileUri,
        String mimeType,
        String state,
        long sizeBytes
) {}
