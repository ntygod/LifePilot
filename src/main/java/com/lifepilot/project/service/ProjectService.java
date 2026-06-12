package com.lifepilot.project.service;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.project.exception.ProjectNotFoundException;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    /**
     * KB 管理器 —— 可选依赖。知识库功能未启用时（例如极简部署或集成测试），
     * {@link #createProject} 会跳过"建默认项目知识库"步骤，项目仍能正常创建。
     */
    @Nullable
    private final KnowledgeBaseManager knowledgeBaseManager;
    @Nullable
    private final MemoryProjectionService projectionService;

    public ProjectService(ProjectRepository projectRepository,
                          MemorySpaceRepository memorySpaceRepository,
                          SessionStoreRepository sessionStoreRepository,
                          JdbcTemplate jdbcTemplate,
                          @Nullable KnowledgeBaseManager knowledgeBaseManager) {
        this(projectRepository, memorySpaceRepository, sessionStoreRepository,
                jdbcTemplate, knowledgeBaseManager, null);
    }

    @Autowired
    public ProjectService(ProjectRepository projectRepository,
                          MemorySpaceRepository memorySpaceRepository,
                          SessionStoreRepository sessionStoreRepository,
                          JdbcTemplate jdbcTemplate,
                          @Nullable KnowledgeBaseManager knowledgeBaseManager,
                          @Nullable MemoryProjectionService projectionService) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
        this.sessionStoreRepository = sessionStoreRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.projectionService = projectionService;
    }

    /**
     * 创建项目并确保其项目级 MemorySpace 存在。
     *
     * <p>入参规范化：{@code instructions} 为 null 视为空字符串；
     * {@code isolation} 为 null 使用默认 {@link ProjectIsolation#defaultValue()}。
     * 重名校验在创建 MemorySpace 之前执行，避免重名时产生孤立的空间记录。</p>
     *
     * <p>若 {@link KnowledgeBaseManager} 可用，会同步创建一个"项目默认知识库"
     * 并绑定到项目的 MemorySpace（memory_space_knowledge_bases）。tags 固定为
     * {@code ["project"]}，{@link #deleteProject} 据此识别"项目默认 KB"并级联
     * 删除本体（用户手动挂到项目的非默认 KB 因 tags 不含 "project" 不会被误删）。</p>
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
        ensureDefaultKnowledgeBase(project, space);
        log.info("创建项目: id={}, name={}, memorySpaceId={}", id, name, space.id());
        return project;
    }

    /**
     * 为新建项目创建默认知识库并绑定到项目 MemorySpace。
     *
     * <p>KB 功能未启用（{@link #knowledgeBaseManager} 为 null）时静默跳过；
     * KB 创建失败不抛出，只记警告 —— 让项目创建在 KB 偶发故障时仍可成功，
     * 后续可通过"项目设置"手动补建 KB。</p>
     */
    private void ensureDefaultKnowledgeBase(Project project, MemorySpace space) {
        if (knowledgeBaseManager == null) {
            log.debug("KnowledgeBaseManager 未注入，跳过项目默认知识库创建: projectId={}", project.id());
            return;
        }
        try {
            KnowledgeBase kb = knowledgeBaseManager.createKnowledgeBase(
                    project.name() + " · 项目知识库",
                    "项目「" + project.name() + "」自动创建的默认知识库",
                    null,
                    null,
                    null,
                    Map.of(),
                    List.of("project")
            );
            memorySpaceRepository.attachKnowledgeBase(space.id(), kb.id());
            log.info("项目默认知识库创建并绑定: projectId={}, kbId={}, spaceId={}",
                    project.id(), kb.id(), space.id());
        } catch (RuntimeException e) {
            log.warn("项目默认知识库创建失败（项目已建成，可稍后手动补建）: projectId={}, error={}",
                    project.id(), e.getMessage());
        }
    }

    /**
     * 查询项目下绑定的知识库 id 列表。
     *
     * <p>当前通过项目的 MemorySpace 反查 memory_space_knowledge_bases 表，
     * 不直接在 projects 表冗余 knowledge_base_id。返回顺序按绑定时间升序，
     * 前端一般取第一个作为"项目默认知识库"。</p>
     *
     * @param projectId 项目 id
     * @return 知识库 id 列表（项目不存在时返回空列表）
     */
    public List<String> findKnowledgeBaseIds(String projectId) {
        return projectRepository.findById(projectId)
                .map(p -> memorySpaceRepository.findKnowledgeBaseIdsForSpace(p.memorySpaceId()))
                .orElseGet(List::of);
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
     * <ol start="0">
     *   <li>级联删除"项目自动建的默认 KB"本体（tag 含 {@code "project"}）——
     *       memory_space_knowledge_bases 仅绑定关系会随第 5 步 FK CASCADE 清空，
     *       但 KB 本体（knowledge_bases 表）不会被任何 FK 带走，必须在此处显式
     *       调 {@link KnowledgeBaseManager#deleteKnowledgeBase} 才能避免留下"孤儿项目 KB"。
     *       识别方式：通过 memory_space_knowledge_bases 反查项目 space 下所有 KB，
     *       只删 tag 含 {@code "project"} 的（避免误删用户手动挂到项目的非默认 KB）；</li>
     *   <li>清理归属此项目的所有 session_store 行 —— FK CASCADE 连带清
     *       chat_turns / session_transcript_entries 等全部子表；</li>
     *   <li>清理项目记忆空间下的 memory_relations —— memory_relations FK 到
     *       memory_entities 不带 CASCADE，必须先于 memory_entities 删；</li>
     *   <li>清理项目记忆空间下的 memory_entities —— FK CASCADE 连带清
     *       memory_entity_versions / memory_entity_provenances；</li>
     *   <li>删 projects 行 —— V15 的 FK 对 memory_spaces 是 RESTRICT，必须先
     *       删 project 再删 space；</li>
     *   <li>删 memory_spaces 行 —— memory_space_knowledge_bases 通过 FK CASCADE 自动清理。</li>
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

        // 0) 级联删项目自动建的默认 KB 本体（只删 tag=["project"] 的，保留用户手动挂的其他 KB）
        //   KnowledgeBaseManager 未启用时（极简部署/集成测试 null 注入）整个步骤跳过，
        //   此时 memory_space_knowledge_bases 关联仍会在 Step 5 被 FK CASCADE 清空，
        //   但孤儿 KB 本体只能通过 Admin 工具后期清理。
        int kbDeletedCount = 0;
        if (knowledgeBaseManager != null) {
            List<String> kbIds = memorySpaceRepository.findKnowledgeBaseIdsForSpace(spaceId);
            for (String kbId : kbIds) {
                Optional<KnowledgeBase> kbOpt = knowledgeBaseManager.getKnowledgeBase(kbId);
                if (kbOpt.isEmpty()) {
                    continue;
                }
                List<String> tags = kbOpt.get().tags();
                // 只删"项目默认 KB"：createProject 建 KB 时 tags=["project"]（见 ensureDefaultKnowledgeBase）
                if (tags != null && tags.contains("project")) {
                    knowledgeBaseManager.deleteKnowledgeBase(kbId);
                    kbDeletedCount++;
                    log.info("级联删除项目默认 KB: projectId={}, kbId={}", id, kbId);
                }
            }
        }
        // 1) 清归属项目的会话（FK CASCADE 带走 session_* 所有子表）
        List<String> sessionIds = sessionStoreRepository.findIdsByProjectId(id);
        if (!sessionIds.isEmpty()) {
            sessionStoreRepository.batchDelete(sessionIds);
        }
        // 2) 主库删除前先收集实体 ID，并在同一事务中登记派生投影删除任务
        List<String> entityIds = jdbcTemplate.queryForList(
                "SELECT id FROM memory_entities WHERE space_id = ?", String.class, spaceId);
        enqueueVectorDeleteTasks(entityIds);
        // 3) 先删 memory_relations（它们 FK 到 memory_entities 无 CASCADE，必须先清）
        int relationsDeleted = jdbcTemplate.update(
                "DELETE FROM memory_relations WHERE space_id = ?", spaceId);
        // 4) 再删 memory_entities（FK CASCADE 连带清 entity_versions / entity_provenances）
        int entitiesDeleted = jdbcTemplate.update(
                "DELETE FROM memory_entities WHERE space_id = ?", spaceId);
        // 5) 先删 project（V15 FK 到 memory_spaces 是 RESTRICT，顺序不能反）
        projectRepository.deleteById(id);
        // 6) 再删 memory_space（带走 memory_space_knowledge_bases）
        memorySpaceRepository.deleteById(spaceId);

        log.info("删除项目级联完成: id={}, spaceId={}, sessions={}, entities={}, relations={}, kbs={}",
                id, spaceId, sessionIds.size(), entitiesDeleted, relationsDeleted, kbDeletedCount);
    }

    private void enqueueVectorDeleteTasks(@Nullable List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return;
        }
        if (projectionService == null) {
            throw new IllegalStateException("MemoryProjectionService 未装配，禁止绕过 outbox 清理项目向量");
        }
        for (String entityId : entityIds) {
            projectionService.enqueueVectorDeleteAfterCommit(entityId);
        }
    }
}
