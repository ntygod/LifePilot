package com.lifepilot.llm;

import java.util.List;

/**
 * 模型场景常量。
 *
 * <p>工作流 YAML 和各模块可直接使用场景字符串，
 * 由 GenerationRouter / EmbeddingRouter / RerankRouter 按能力路由。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class LlmScene {

    /** 通用对话。 */
    public static final String CHAT = "chat";
    /** 知识实体提取。 */
    public static final String KNOWLEDGE_EXTRACTION = "knowledge_extraction";
    /** 记忆压缩。 */
    public static final String MEMORY_COMPRESSION = "memory_compression";
    /** 向量化。 */
    public static final String EMBEDDING = "embedding";
    /** Skill 自动生成。 */
    public static final String SKILL_GENERATION = "skill_generation";
    /** 知识精排。 */
    public static final String KNOWLEDGE_RERANK = "knowledge_rerank";
    /** ReAct Agent 循环。 */
    public static final String AGENT_REACT = "agent_react";
    /** 主动提醒文案生成。 */
    public static final String PROACTIVE_REMINDER = "proactive_reminder";

    private LlmScene() {
    }

    /**
     * 返回全部已注册场景常量。
     */
    public static List<String> all() {
        return List.of(
                CHAT,
                KNOWLEDGE_EXTRACTION,
                MEMORY_COMPRESSION,
                EMBEDDING,
                SKILL_GENERATION,
                KNOWLEDGE_RERANK,
                AGENT_REACT,
                PROACTIVE_REMINDER
        );
    }
}
