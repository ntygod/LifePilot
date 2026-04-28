package com.lifepilot.llm.profile;

/**
 * 结构化输出协议支持级别。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum StructuredOutputMode {
    /** 完整 JSON Schema 模式（OpenAI 官方 / 通义） */
    JSON_SCHEMA,
    /** JSON Object 模式（DeepSeek / 智谱 / 火山等） */
    JSON_OBJECT,
    /** 协议不支持，回退到提示词约束 */
    PROMPT_ONLY
}
