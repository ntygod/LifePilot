package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 单轮临时覆盖的会话配置。
 *
 * <p>用于"这一条消息临时用 XX 模型 / 临时挂 YY 知识库 / 临时调温度"的场景——
 * 字段值只影响当前 turn 的 {@link com.lifepilot.agent.model.AgentRequest} 构造，
 * <b>不写入 {@code session_store.config_json}</b>。</p>
 *
 * <p>所有字段均为可选，为空时回退到持久化的会话配置。前端"@ 叠加知识库 + 单轮模型切换"
 * 由此字段驱动，取代过去的"发送前 PATCH config → 发送后 PATCH 还原"双 PATCH race 实现。</p>
 *
 * @param preferredProviderId  单轮偏好模型服务 ID
 * @param temperature          单轮温度
 * @param maxSteps             单轮最大步数
 * @param maxDurationSeconds   单轮最大执行时长（秒）
 * @param knowledgeBaseIds     单轮临时生效的知识库 ID 列表（覆盖而非追加）
 * @param memoryContextMode    单轮记忆上下文模式：auto / focused / off
 * @author zsg
 * @since 2026-05-08
 */
public record SessionConfigOverride(
        @Nullable String preferredProviderId,
        @Nullable Double temperature,
        @Nullable Integer maxSteps,
        @Nullable Integer maxDurationSeconds,
        @Nullable List<String> knowledgeBaseIds,
        @Nullable String memoryContextMode
) {
    public SessionConfigOverride {
        knowledgeBaseIds = (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty())
                ? null
                : List.copyOf(knowledgeBaseIds);
        memoryContextMode = normalizeMemoryContextMode(memoryContextMode);
    }

    /** 任何字段非空都视为有效覆盖。 */
    public boolean isEmpty() {
        return preferredProviderId == null
                && temperature == null
                && maxSteps == null
                && maxDurationSeconds == null
                && knowledgeBaseIds == null
                && memoryContextMode == null;
    }

    @Nullable
    private static String normalizeMemoryContextMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "auto", "focused", "off" -> normalized;
            default -> null;
        };
    }
}
