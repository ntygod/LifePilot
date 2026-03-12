package com.lifepilot.llm;

import java.util.List;

/**
 * LLM task-intent scene constants.
 */
public final class LlmScene {

    public static final String INTENT_UNDERSTANDING = "intent_understanding";
    public static final String TASK_PLANNING = "task_planning";
    public static final String CHAT = "chat";
    public static final String CODE_GENERATION = "code_generation";
    public static final String KNOWLEDGE_EXTRACTION = "knowledge_extraction";
    public static final String MEMORY_COMPRESSION = "memory_compression";
    public static final String DOCUMENT_SUMMARY = "document_summary";
    public static final String EMBEDDING = "embedding";
    public static final String PROACTIVE_REASONING = "proactive_reasoning";
    public static final String AGENT_REASONING = "agent_reasoning";
    public static final String AGENT_TOOL_CALLING = "agent_tool_calling";
    public static final String AGENT_GENERATION = "agent_generation";
    public static final String SKILL_GENERATION = "skill_generation";
    public static final String KNOWLEDGE_RERANK = "knowledge_rerank";

    private LlmScene() {
    }

    public static List<String> all() {
        return List.of(
                INTENT_UNDERSTANDING,
                TASK_PLANNING,
                CHAT,
                CODE_GENERATION,
                KNOWLEDGE_EXTRACTION,
                MEMORY_COMPRESSION,
                DOCUMENT_SUMMARY,
                EMBEDDING,
                PROACTIVE_REASONING,
                AGENT_REASONING,
                AGENT_TOOL_CALLING,
                AGENT_GENERATION,
                SKILL_GENERATION,
                KNOWLEDGE_RERANK
        );
    }
}
