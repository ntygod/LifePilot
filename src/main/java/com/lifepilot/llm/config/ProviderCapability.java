package com.lifepilot.llm.config;

/**
 * LLM Provider 能力枚举。
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum ProviderCapability {
    /** 对话能力 */
    CHAT,
    /** 向量嵌入能力 */
    EMBEDDING,
    /** 结构化输出能力 */
    STRUCTURED_OUTPUT,
    /** 函数调用能力 */
    FUNCTION_CALLING,
    /** 流式输出能力 */
    STREAMING,
    /** 视觉理解能力 */
    VISION,
    /** 文字转语音能力 */
    TTS,
    /** 语音转文字能力 */
    STT,
    /** 重排序能力 */
    RERANK
}

