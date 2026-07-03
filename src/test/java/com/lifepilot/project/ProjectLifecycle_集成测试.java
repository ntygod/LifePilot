package com.lifepilot.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemorySpaceType;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import com.lifepilot.project.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 1 Task 24 —— 项目生命周期端到端集成测试。
 *
 * <p>这是 Plan 1 最终验收测试的集中入口之一，目标是按 spec §12 的视角
 * 直接断言 "建项目 → 关联 MemorySpace 自动创建 → 删项目级联清理 →
 * 再查不到任何残留"，打通 {@link ProjectService} 的"创建 / 查询 / 删除"
 * 与 {@link MemorySpaceRepository} 的联动闭环。</p>
 *
 * <p>实现策略：沿用仓库既有的 SingleConnectionDataSource + 内存 SQLite
 * + 手动建表 pattern（见 {@code ProjectDeletion_级联集成测试}），
 * 而不是 {@code @SpringBootTest + @Transactional} ——
 * 单连接 SQLite 下 Spring 事务在非 Spring 管理的 DataSource 上意义有限，
 * 手动建表能精确控制 FK 约束语义，保持与级联测试的风格一致。</p>
 *
 * <p>对应 spec §12 验收点：
 * <ul>
 *   <li>验收 1（部分）：建项目 → Project 实体与 MemorySpace 同步创建</li>
 *   <li>验收 9：删除项目 → 项目 + MemorySpace + 归属会话全部物理清除</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProjectLifecycle_集成测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ProjectService projectService;
    private MemorySpaceRepository memorySpaceRepository;
    private ProjectRepository projectRepository;
    private SessionStoreRepository sessionStoreRepository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 必须开启 FK 约束，否则级联行为不会执行
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        createSchema();

        memorySpaceRepository = new MemorySpaceRepository(jdbcTemplate, new ObjectMapper());
        projectRepository = new ProjectRepository(jdbcTemplate);
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, new ObjectMapper(), null);
        var projectionService = mock(MemoryProjectionService.class);
        var knowledgeBaseManager = mock(KnowledgeBaseManager.class);
        stubKnowledgeBaseManager(knowledgeBaseManager);
        projectService = new ProjectService(
                projectRepository, memorySpaceRepository, sessionStoreRepository,
                jdbcTemplate, knowledgeBaseManager, projectionService);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    /** 只建 ProjectService 真正触达的表，保持与 ProjectDeletion_级联集成测试 的结构一致。 */
    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE memory_spaces (
                    id TEXT PRIMARY KEY,
                    space_key TEXT NOT NULL UNIQUE,
                    space_type TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    owner_type TEXT,
                    owner_id TEXT,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE session_store (
                    session_id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL DEFAULT 'web',
                    chat_type TEXT NOT NULL DEFAULT 'chat',
                    title TEXT NOT NULL,
                    summary TEXT,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_activity_at TEXT NOT NULL,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    context_tokens_estimate INTEGER NOT NULL DEFAULT 0,
                    compaction_count INTEGER NOT NULL DEFAULT 0,
                    memory_flush_at TEXT,
                    active_branch_id TEXT NOT NULL DEFAULT 'main',
                    project_id TEXT
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE chat_turns (
                    turn_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL REFERENCES session_store(session_id) ON DELETE CASCADE,
                    last_action TEXT NOT NULL,
                    status TEXT NOT NULL,
                    request_payload_json TEXT NOT NULL DEFAULT '{}',
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE session_transcript_entries (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    entry_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE memory_entities (
                    id TEXT PRIMARY KEY,
                    space_id TEXT NOT NULL,
                    memory_scope TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    canonical_name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    reality_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    access_count INTEGER NOT NULL DEFAULT 0,
                    last_accessed_at TEXT,
                    first_seen_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (space_id) REFERENCES memory_spaces(id)
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE memory_relations (
                    id TEXT PRIMARY KEY,
                    space_id TEXT NOT NULL,
                    source_entity_id TEXT NOT NULL,
                    target_entity_id TEXT NOT NULL,
                    relation_type TEXT NOT NULL,
                    reality_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (space_id) REFERENCES memory_spaces(id),
                    FOREIGN KEY (source_entity_id) REFERENCES memory_entities(id),
                    FOREIGN KEY (target_entity_id) REFERENCES memory_entities(id)
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE projects (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    instructions TEXT NOT NULL DEFAULT '',
                    isolation TEXT NOT NULL DEFAULT 'ISOLATED'
                                CHECK (isolation IN ('ISOLATED', 'SHARED')),
                    memory_space_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(name),
                    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE RESTRICT
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE memory_space_knowledge_bases (
                    memory_space_id TEXT NOT NULL,
                    knowledge_base_id TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (memory_space_id, knowledge_base_id),
                    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE
                )""");
    }

    private void stubKnowledgeBaseManager(KnowledgeBaseManager knowledgeBaseManager) {
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> projectKnowledgeBase(UUID.randomUUID().toString(), invocation.getArgument(0)));
        when(knowledgeBaseManager.getKnowledgeBase(anyString()))
                .thenAnswer(invocation -> Optional.of(projectKnowledgeBase(invocation.getArgument(0), "项目知识库")));
    }

    private KnowledgeBase projectKnowledgeBase(String id, String name) {
        Instant now = Instant.now();
        return new KnowledgeBase(
                id,
                name,
                "自动创建的默认项目知识库",
                null,
                null,
                "smart",
                Map.of(),
                0,
                0,
                List.of("project"),
                now,
                now);
    }

    @Test
    void 建项目_自动创建关联MemorySpace_并在仓储中可查() {
        Project p = projectService.createProject(
                "集成测试项目", "你是一位严谨的论文助手", ProjectIsolation.ISOLATED);

        // 项目本身写入 projects 表
        assertThat(projectRepository.findById(p.id())).isPresent();
        assertThat(p.name()).isEqualTo("集成测试项目");
        assertThat(p.instructions()).isEqualTo("你是一位严谨的论文助手");
        assertThat(p.isolation()).isEqualTo(ProjectIsolation.ISOLATED);

        // 关联的 MemorySpace 被自动创建且可查
        assertThat(memorySpaceRepository.findById(p.memorySpaceId())).isPresent();
        assertThat(memorySpaceRepository.findById(p.memorySpaceId()).orElseThrow().spaceType())
                .isEqualTo(MemorySpaceType.PROJECT);
    }

    @Test
    void 建项目_查项目_删项目_MemorySpace级联清理() {
        Project p = projectService.createProject(
                "生命周期测试", "", ProjectIsolation.ISOLATED);
        String memorySpaceId = p.memorySpaceId();

        // 先确认都在
        assertThat(projectService.getProject(p.id())).isPresent();
        assertThat(memorySpaceRepository.findById(memorySpaceId)).isPresent();

        // 删项目
        projectService.deleteProject(p.id());

        // 项目和关联 space 均已清理
        assertThat(projectService.getProject(p.id())).isEmpty();
        assertThat(memorySpaceRepository.findById(memorySpaceId)).isEmpty();
    }

    @Test
    void 重名项目创建失败_且不残留孤立MemorySpace() {
        projectService.createProject("重名测试", "", ProjectIsolation.ISOLATED);

        int spacesBeforeSecondAttempt = countRows("memory_spaces");

        // 第二次用同名创建应抛 IAE
        assertThatThrownBy(() -> projectService.createProject(
                "重名测试", "", ProjectIsolation.ISOLATED))
                .isInstanceOf(IllegalArgumentException.class);

        // 重名抛异常后不应产生孤立的 project MemorySpace（existsByName 检查在建 space 之前）
        int spacesAfterSecondAttempt = countRows("memory_spaces");
        assertThat(spacesAfterSecondAttempt).isEqualTo(spacesBeforeSecondAttempt);
    }

    @Test
    void 建项目_在项目下建会话_删项目时会话被级联清理_主账户会话不受影响() {
        // 场景：一次典型的端到端项目生命周期 —— 建项目、建项目会话、建主账户会话、删项目
        Project project = projectService.createProject(
                "生命周期端到端", "", ProjectIsolation.ISOLATED);

        Instant now = Instant.now();
        String projectSessionId = UUID.randomUUID().toString();
        String mainSessionId = UUID.randomUUID().toString();
        sessionStoreRepository.save(new ChatSession(projectSessionId, "项目下对话",
                null, 0, false, false, null, now, now, project.id()));
        sessionStoreRepository.save(new ChatSession(mainSessionId, "主账户下对话",
                null, 0, false, false, null, now, now, null));

        // 删项目
        projectService.deleteProject(project.id());

        // 项目下的会话被清 / 主账户会话保留
        assertThat(sessionStoreRepository.findBySessionId(projectSessionId)).isEmpty();
        assertThat(sessionStoreRepository.findBySessionId(mainSessionId)).isPresent();

        // 项目和 space 也清掉
        assertThat(projectRepository.findById(project.id())).isEmpty();
        assertThat(memorySpaceRepository.findById(project.memorySpaceId())).isEmpty();
    }

    private int countRows(String tableName) {
        Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return c != null ? c : 0;
    }
}
