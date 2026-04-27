package com.lifepilot.llm.profile;

/**
 * 基础 SDK 适配器类型 — 决定使用哪条 Spring AI ChatModel 路径。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum BaseAdapterType {
    /** OpenAI 兼容 API（DeepSeek / Qwen / OpenAI 官方 / 智谱 / 月之暗面 / 火山等共用） */
    OPENAI_BASE,
    /** Anthropic 原生 API */
    ANTHROPIC_BASE,
    /** 本地 Ollama 服务 */
    OLLAMA,
    /** HuggingFace TEI（embedding / rerank 专用） */
    TEI
}
