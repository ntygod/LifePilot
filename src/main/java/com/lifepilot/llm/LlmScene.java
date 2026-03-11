package com.lifepilot.llm;

import java.util.List;

/**
 * LLM 调用场景常量。
 *
 * <p>调用方通过场景声明意图，路由层根据场景选择最优 Provider。
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class LlmScene {

    /** 意图理解 */
    public static final String INTENT_UNDERSTANDING = "intent_understanding";
    /** 任务规划 */
    public static final String TASK_PLANNING = "task_planning";
    /** 通用对话 */
    public static final String CHAT = "chat";
    /** 代码生成 */
    public static final String CODE_GENERATION = "code_generation";
    /** 知识抽取 */
    public static final String KNOWLEDGE_EXTRACTION = "knowledge_extraction";
    /** 记忆压缩 */
    public static final String MEMORY_COMPRESSION = "memory_compression";
    /** 文档摘要 */
    public static final String DOCUMENT_SUMMARY = "document_summary";
    /** 向量嵌入 */
    public static final String EMBEDDING = "embedding";
    /** 主动推理 */
    public static final String PROACTIVE_REASONING = "proactive_reasoning";
    /** Agent 推理（意图理解 / 任务规划 / 反思评估） */
    public static final String AGENT_REASONING = "agent_reasoning";
    /** Agent 工具调用 */
    public static final String AGENT_TOOL_CALLING = "agent_tool_calling";
    /** Agent 响应生成 */
    public static final String AGENT_GENERATION = "agent_generation";
    /** Skill 生成 */
    public static final String SKILL_GENERATION = "skill_generation";
    /** 知识库精排 */
    public static final String KNOWLEDGE_RERANK = "knowledge_rerank";

    private LlmScene() {
        // 阻止实例化
    }

    /**
     * 返回所有场景常量的不可变列表。
     *
     * @return 场景列表
     */
    public static List<String> all() {
        return List.of(
                INTENT_UNDERSTANDING, TASK_PLANNING, CHAT, CODE_GENERATION,
                KNOWLEDGE_EXTRACTION, MEMORY_COMPRESSION, DOCUMENT_SUMMARY,
                EMBEDDING, PROACTIVE_REASONING,
                AGENT_REASONING, AGENT_TOOL_CALLING, AGENT_GENERATION,
                SKILL_GENERATION, KNOWLEDGE_RERANK
        );
    }
}
