package com.lifepilot.project.model;

import java.time.Instant;

/**
 * 项目（领域级任务容器）。
 *
 * @param id               UUID
 * @param name             项目名（1..64 字符，同账户唯一）
 * @param instructions     指示词（0..1000 字符，注入对话 system prompt）
 * @param isolation        记忆隔离模式
 * @param memorySpaceId    关联的 MemorySpace id（每个项目对应一个 PROJECT 类型 space）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 *
 * @author zsg
 * @since 2026-04-23
 */
public record Project(
        String id,
        String name,
        String instructions,
        ProjectIsolation isolation,
        String memorySpaceId,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_INSTRUCTIONS_LENGTH = 1000;

    public Project {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("项目名超过 " + MAX_NAME_LENGTH + " 字符");
        }
        if (instructions == null) {
            instructions = "";
        }
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("指示词超过 " + MAX_INSTRUCTIONS_LENGTH + " 字符");
        }
        if (isolation == null) {
            throw new IllegalArgumentException("isolation 不能为空");
        }
        if (memorySpaceId == null || memorySpaceId.isBlank()) {
            throw new IllegalArgumentException("memorySpaceId 不能为空");
        }
    }
}
