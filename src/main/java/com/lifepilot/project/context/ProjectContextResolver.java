package com.lifepilot.project.context;

import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.exception.ProjectNotFoundException;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 从 projectId 解析 ProjectContext。
 *
 * <p>主账户 personal / experience 空间通过 {@code MemorySpaceRepository.ensureDefault*}
 * 懒初始化，保证 context 构造永远不会因 "默认空间不存在" 失败。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
public class ProjectContextResolver {

    private final ProjectRepository projectRepository;
    private final MemorySpaceRepository memorySpaceRepository;

    public ProjectContextResolver(ProjectRepository projectRepository,
                                  MemorySpaceRepository memorySpaceRepository) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    /**
     * 解析项目上下文。
     *
     * @param projectId 项目 id，null 表示主账户对话
     * @return ProjectContext
     * @throws ProjectNotFoundException projectId 非 null 但项目不存在
     */
    public ProjectContext resolve(@Nullable String projectId) {
        MemorySpace personal = memorySpaceRepository.ensureDefaultPersonalSpace();
        MemorySpace experience = memorySpaceRepository.ensureDefaultExperienceSpace();
        if (projectId == null) {
            return ProjectContext.personal(personal.id(), experience.id());
        }
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        return new ProjectContext(
                p.id(),
                p.memorySpaceId(),
                personal.id(),
                experience.id(),
                p.isolation() == ProjectIsolation.ISOLATED
        );
    }
}
