package com.lifepilot.project.service;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.exception.ProjectNotFoundException;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * PROJECT 级 MemorySpace，删除项目时完整级联清理项目下的会话和项目空间记忆。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final MemorySpaceRepository memorySpaceRepository;
    private final SessionStoreRepository sessionStoreRepository;
    private final JdbcTemplate jdbcTemplate;

    public ProjectService(ProjectRepository projectRepository,
                          MemorySpaceRepository memorySpaceRepository,
                          SessionStoreRepository sessionStoreRepository,
                          JdbcTemplate jdbcTemplate) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
        this.sessionStoreRepository = sessionStoreRepository;
        this.jdbcTemplate = jdbcTemplate;
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
        log.info("创建项目: id={}, name={}, memorySpaceId={}", id, name, space.id());
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
                .orElseThrow(() -> new ProjectNotFoundException(id));
        // 改名时做重名校验，与 createProject 对称，避免走到 DB UNIQUE 约束兜底导致异常类型不一致
        if (!existing.name().equals(name) && projectRepository.existsByNameAndIdNot(name, id)) {
            throw new IllegalArgumentException("项目名已存在：" + name);
        }
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
        log.info("更新项目: id={}, name={}", id, name);
        return updated;
    }

    /**
     * 删除项目并完整级联清理所有关联资源。
     *
     * <p>级联顺序（FK 约束决定，必须严格按此顺序执行）：</p>
     * <ol>
     *   <li>清理归属此项目的所有 session_store 行 —— FK CASCADE 连带清
     *       chat_turns / session_transcript_entries 等全部子表；</li>
     *   <li>清理项目记忆空间下的 memory_relations —— memory_relations FK 到
     *       memory_entities 不带 CASCADE，必须先于 memory_entities 删；</li>
     *   <li>清理项目记忆空间下的 memory_entities —— FK CASCADE 连带清
     *       memory_entity_versions / memory_entity_provenances；</li>
     *   <li>删 projects 行 —— V15 的 FK 对 memory_spaces 是 RESTRICT，必须先
     *       删 project 再删 space；</li>
     *   <li>删 memory_spaces 行 —— memory_space_knowledge_bases /
     *       memory_space_datastores 通过 FK CASCADE 自动清理。</li>
     * </ol>
     *
     * <p>注意 memory_entities / memory_relations 两张表 FK 到 memory_spaces
     * 的是 <b>RESTRICT</b>（V1:337/404），因此不能依赖 memory_spaces 的删除
     * 自动带走它们，必须在代码里显式清空。</p>
     *
     * @param id 项目 id
     * @throws ProjectNotFoundException 项目不存在时
     */
    @Transactional
    public void deleteProject(String id) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
        String spaceId = existing.memorySpaceId();

        // 1) 清归属项目的会话（FK CASCADE 带走 session_* 所有子表）
        List<String> sessionIds = sessionStoreRepository.findIdsByProjectId(id);
        if (!sessionIds.isEmpty()) {
            sessionStoreRepository.batchDelete(sessionIds);
        }
        // 2) 先删 memory_relations（它们 FK 到 memory_entities 无 CASCADE，必须先清）
        int relationsDeleted = jdbcTemplate.update(
                "DELETE FROM memory_relations WHERE space_id = ?", spaceId);
        // 3) 再删 memory_entities（FK CASCADE 连带清 entity_versions / entity_provenances）
        int entitiesDeleted = jdbcTemplate.update(
                "DELETE FROM memory_entities WHERE space_id = ?", spaceId);
        // 4) 先删 project（V15 FK 到 memory_spaces 是 RESTRICT，顺序不能反）
        projectRepository.deleteById(id);
        // 5) 再删 memory_space（带走 memory_space_knowledge_bases / _datastores）
        memorySpaceRepository.deleteById(spaceId);

        log.info("删除项目级联完成: id={}, spaceId={}, sessions={}, entities={}, relations={}",
                id, spaceId, sessionIds.size(), entitiesDeleted, relationsDeleted);
    }
}
