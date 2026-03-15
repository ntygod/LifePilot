package com.lifepilot.llm;

import java.util.List;

/**
 * LLM 任务意图场景常量。
 *
 * <p>仅保留生产代码中实际使用的场景，未使用的场景已清理。
 * 工作流 YAML 中可自由使用任意场景字符串，LlmRouter 会按能力路由兜底。
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class LlmScene {

    /** 通用对话 */
    public static final String CHAT = "chat";
    /** 知识实体提取（AUDN） */
    public static final String KNOWLEDGE_EXTRACTION = "knowledge_extraction";
    /** 记忆压缩 */
    public static final String MEMORY_COMPRESSION = "memory_compression";
    /** 向量嵌入 */
    public static final String EMBEDDING = "embedding";
    /** 主动推理 */
    public static final String PROACTIVE_REASONING = "proactive_reasoning";
    /** Skill 自动生成 */
    public static final String SKILL_GENERATION = "skill_generation";
    /** 知识精排 */
    public static final String KNOWLEDGE_RERANK = "knowledge_rerank";
    /** ReAct Agent 循环 */
    public static final String AGENT_REACT = "agent_react";

    private LlmScene() {
    }

    /**
     * 返回所有已注册场景常量列表。
     */
    public static List<String> all() {
        return List.of(
                CHAT,
                KNOWLEDGE_EXTRACTION,
                MEMORY_COMPRESSION,
                EMBEDDING,
                PROACTIVE_REASONING,
                SKILL_GENERATION,
                KNOWLEDGE_RERANK,
                AGENT_REACT
        );
    }
}
