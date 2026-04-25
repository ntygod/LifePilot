package com.lifepilot.interaction.web.model.project;

import com.lifepilot.project.model.Project;

import java.time.Instant;
import java.util.List;

/**
 * 项目 API 响应 DTO。
 *
 * <p>与领域 {@link Project} 的差异：{@code isolation} 以字符串形式暴露给前端，
 * 避免枚举序列化耦合；额外附带 {@code knowledgeBaseIds} —— 通过项目
 * MemorySpace 反查 {@code memory_space_knowledge_bases} 得到，前端建完项目
 * 后会用它的第一个元素作为"项目默认知识库"承接初始文件上传。</p>
 *
 * @param id               项目 id
 * @param name             项目名
 * @param instructions     指示词
 * @param isolation        隔离模式字符串（ISOLATED / SHARED）
 * @param memorySpaceId    关联的 MemorySpace id
 * @param knowledgeBaseIds 本项目绑定的知识库 id 列表（按绑定时间升序）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @author zsg
 * @since 2026-04-23
 */
public record ProjectResponse(
        String id,
        String name,
        String instructions,
        String isolation,
        String memorySpaceId,
        List<String> knowledgeBaseIds,
        Instant createdAt,
        Instant updatedAt
) {
    public ProjectResponse {
        knowledgeBaseIds = knowledgeBaseIds != null ? List.copyOf(knowledgeBaseIds) : List.of();
    }

    /**
     * 从领域模型构建 DTO，{@code knowledgeBaseIds} 默认空列表。
     *
     * <p>调用方需要返回项目的知识库绑定时应使用 {@link #from(Project, List)}
     * 显式传入，避免在 Controller 层忘记拉取关联数据。</p>
     */
    public static ProjectResponse from(Project p) {
        return from(p, List.of());
    }

    public static ProjectResponse from(Project p, List<String> knowledgeBaseIds) {
        return new ProjectResponse(
                p.id(),
                p.name(),
                p.instructions(),
                p.isolation().name(),
                p.memorySpaceId(),
                knowledgeBaseIds,
                p.createdAt(),
                p.updatedAt()
        );
    }
}
