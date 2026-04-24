package com.lifepilot.interaction.web.model.project;

import com.lifepilot.project.model.Project;

import java.time.Instant;

/**
 * 项目 API 响应 DTO。
 *
 * <p>与领域 {@link Project} 的差异：{@code isolation} 以字符串形式暴露给前端，
 * 避免枚举序列化耦合。其余字段直透。</p>
 *
 * @param id            项目 id
 * @param name          项目名
 * @param instructions  指示词
 * @param isolation     隔离模式字符串（ISOLATED / SHARED）
 * @param memorySpaceId 关联的 MemorySpace id
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @author zsg
 * @since 2026-04-23
 */
public record ProjectResponse(
        String id,
        String name,
        String instructions,
        String isolation,
        String memorySpaceId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.id(),
                p.name(),
                p.instructions(),
                p.isolation().name(),
                p.memorySpaceId(),
                p.createdAt(),
                p.updatedAt()
        );
    }
}
