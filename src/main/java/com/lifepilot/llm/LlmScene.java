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
    /** Skill 自动生成。 */
    public static final String SKILL_GENERATION = "skill_generation";
    /** ReAct Agent 循环。 */
    public static final String AGENT_REACT = "agent_react";
    /** 后台分析（对比学习、经验合并、子任务反思、经验总结）。 */
    public static final String BACKGROUND_ANALYSIS = "background_analysis";

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
                SKILL_GENERATION,
                AGENT_REACT,
                BACKGROUND_ANALYSIS
        );
    }
}
