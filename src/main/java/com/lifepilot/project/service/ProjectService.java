package com.lifepilot.project.service;

import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目应用服务。
 *
 * <p>负责项目 CRUD 与关联 MemorySpace 的联动：创建项目时同步建对应的
 * PROJECT 级 MemorySpace，删除项目时级联删除 space。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final MemorySpaceRepository memorySpaceRepository;

    public ProjectService(ProjectRepository projectRepository,
                          MemorySpaceRepository memorySpaceRepository) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    /**
     * 创建项目并确保其项目级 MemorySpace 存在。
     *
     * <p>入参规范化：{@code instructions} 为 null 视为空字符串；
     * {@code isolation} 为 null 使用默认 {@link ProjectIsolation#defaultValue()}。
     * 重名校验在创建 MemorySpace 之前执行，避免重名时产生孤立的空间记录。</p>
     */
    @Transactional
    public Project createProject(String name, String instructions, ProjectIsolation isolation) {
        if (projectRepository.existsByName(name)) {
            throw new IllegalArgumentException("项目名已存在：" + name);
        }
        String id = UUID.randomUUID().toString();
        MemorySpace space = memorySpaceRepository.ensureProjectSpace(id);
        Instant now = Instant.now();
        Project project = new Project(
                id,
                name,
                instructions != null ? instructions : "",
                isolation != null ? isolation : ProjectIsolation.defaultValue(),
                space.id(),
                now,
                now
        );
        projectRepository.insert(project);
        return project;
    }

    /** 列出所有项目（按创建时间倒序）。 */
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    /** 按 id 查询项目。 */
    public Optional<Project> getProject(String id) {
        return projectRepository.findById(id);
    }

    /**
     * 更新项目的 name / instructions / isolation。
     *
     * <p>不可变字段：id、memorySpaceId、createdAt。
     * 若 {@code instructions} 或 {@code isolation} 传入 null，则保留原值。</p>
     */
    @Transactional
    public Project updateProject(String id, String name, String instructions,
                                 ProjectIsolation isolation) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
        Project updated = new Project(
                existing.id(),
                name,
                instructions != null ? instructions : existing.instructions(),
                isolation != null ? isolation : existing.isolation(),
                existing.memorySpaceId(),
                existing.createdAt(),
                Instant.now()
        );
        projectRepository.update(updated);
        return updated;
    }

    /** 物理删除项目并级联删除其 MemorySpace。 */
    @Transactional
    public void deleteProject(String id) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
        projectRepository.deleteById(id);
        memorySpaceRepository.deleteById(existing.memorySpaceId());
    }
}
