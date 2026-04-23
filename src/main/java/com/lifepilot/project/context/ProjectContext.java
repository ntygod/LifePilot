package com.lifepilot.project.context;

import org.springframework.lang.Nullable;

/**
 * 请求/对话的项目上下文载体。
 *
 * <p>projectId 为 null 表示主账户对话；非 null 且 isolated=true 表示隔离项目对话。
 * 下游记忆检索/写入路径依据本 context 决定 MemorySpace 的读写范围。</p>
 *
 * @param projectId         项目 id（主账户对话时为 null）
 * @param projectSpaceId    项目 MemorySpace id（主账户对话时为 null）
 * @param personalSpaceId   主账户 personal MemorySpace id（永远非 null）
 * @param experienceSpaceId 主账户 experience MemorySpace id（永远非 null）
 * @param isolated          当前项目是否 ISOLATED（主账户对话时为 false）
 *
 * @author zsg
 * @since 2026-04-23
 */
public record ProjectContext(
        @Nullable String projectId,
        @Nullable String projectSpaceId,
        String personalSpaceId,
        String experienceSpaceId,
        boolean isolated
) {

    /**
     * 构造主账户对话的上下文（无项目归属）。
     */
    public static ProjectContext personal(String personalSpaceId, String experienceSpaceId) {
        return new ProjectContext(null, null, personalSpaceId, experienceSpaceId, false);
    }
}
