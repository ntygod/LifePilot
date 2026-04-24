package com.lifepilot.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.exception.ProjectNotFoundException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectService.deleteProject 完整级联清理集成测试。
 *
 * <p>关键是使用真实的 SQLite FK 约束（PRAGMA foreign_keys = ON）+ 对照 V1/V15/V17
 * 建表，验证级联顺序的正确性。只建 deleteProject 会触达的关联表：
 * session_store、session_transcript_entries、chat_turns、memory_spaces、
 * memory_entities、memory_entity_versions、memory_entity_provenances、
 * memory_relations、memory_relation_versions、projects。</p>
 *
 * <p>SingleConnectionDataSource + 无 Spring 上下文时，ProjectService 上的
 * {@code @Transactional} 不生效；但级联顺序的正确性不依赖事务 —— 即便每个
 * 语句独立提交，FK 约束也会即时校验（顺序错了就会立即报 FK 错误）。因此
 * 本测试不引入事务管理器，保持与其他集成测试（如 MemorySpaceRepository_项目空间集成测试）
 * 一致的轻量风格。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProjectDeletion_级联集成测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ProjectService service;
    private SessionStoreRepository sessionStoreRepository;
    private MemorySpaceRepository memorySpaceRepository;
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 开启 FK 约束（默认 SQLite 不开，本测试需要真实约束验证）
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        createSchema();

        memorySpaceRepository = new MemorySpaceRepository(jdbcTemplate, new ObjectMapper());
        projectRepository = new ProjectRepository(jdbcTemplate);
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, new ObjectMapper(), null);
        // KnowledgeBaseManager = null：本测试集中于删除级联，不覆盖 KB 自动创建路径
        service = new ProjectService(projectRepository, memorySpaceRepository,
                sessionStoreRepository, jdbcTemplate, null);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    /** 按 V1 + V15 + V17 + V18 结构建表，保持 FK 约束与生产一致（用于级联顺序校验）。 */
    private void createSchema() {
        // memory_spaces
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

        // session_store（含 V17 project_id）
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

        // chat_turns（FK CASCADE to session_store）
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

        // session_transcript_entries（FK CASCADE to session_store）
        jdbcTemplate.execute("""
                CREATE TABLE session_transcript_entries (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    entry_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )""");

        // memory_entities（FK to memory_spaces RESTRICT —— 对照 V1 原始定义）
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

        // memory_entity_versions（FK CASCADE to memory_entities）
        jdbcTemplate.execute("""
                CREATE TABLE memory_entity_versions (
                    id TEXT PRIMARY KEY,
                    entity_id TEXT NOT NULL,
                    version_no INTEGER NOT NULL,
                    description TEXT,
                    properties_json TEXT,
                    extraction_confidence REAL NOT NULL DEFAULT 0.0,
                    importance_score REAL NOT NULL DEFAULT 0.5,
                    is_current INTEGER NOT NULL DEFAULT 1,
                    valid_from TEXT NOT NULL,
                    valid_to TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
                    UNIQUE (entity_id, version_no)
                )""");

        // memory_entity_provenances（FK CASCADE to memory_entities / versions）
        jdbcTemplate.execute("""
                CREATE TABLE memory_entity_provenances (
                    id TEXT PRIMARY KEY,
                    entity_id TEXT NOT NULL,
                    version_id TEXT,
                    origin_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                    confidence REAL NOT NULL DEFAULT 0.0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (entity_id) REFERENCES memory_entities(id) ON DELETE CASCADE,
                    FOREIGN KEY (version_id) REFERENCES memory_entity_versions(id) ON DELETE CASCADE
                )""");

        // memory_relations（FK to memory_spaces RESTRICT + FK to memory_entities RESTRICT）
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

        // memory_relation_versions（FK CASCADE to memory_relations）
        jdbcTemplate.execute("""
                CREATE TABLE memory_relation_versions (
                    id TEXT PRIMARY KEY,
                    relation_id TEXT NOT NULL,
                    version_no INTEGER NOT NULL,
                    strength REAL NOT NULL DEFAULT 0.5,
                    properties_json TEXT,
                    is_current INTEGER NOT NULL DEFAULT 1,
                    valid_from TEXT NOT NULL,
                    valid_to TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (relation_id) REFERENCES memory_relations(id) ON DELETE CASCADE,
                    UNIQUE (relation_id, version_no)
                )""");

        // projects（FK to memory_spaces RESTRICT —— V15）
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

        // memory_space_knowledge_bases / memory_space_datastores 用来验证 FK CASCADE
        jdbcTemplate.execute("""
                CREATE TABLE memory_space_knowledge_bases (
                    memory_space_id TEXT NOT NULL,
                    knowledge_base_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (memory_space_id, knowledge_base_id),
                    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id) ON DELETE CASCADE
                )""");
    }

    /** 落一个项目 + 其 project memory_space，返回 project。 */
    private Project 创建一个项目(String name) {
        String projectId = UUID.randomUUID().toString();
        MemorySpace space = memorySpaceRepository.ensureProjectSpace(projectId);
        Instant now = Instant.now();
        Project project = new Project(projectId, name, "", ProjectIsolation.ISOLATED,
                space.id(), now, now);
        projectRepository.insert(project);
        return project;
    }

    /** 在 space 下造一个 memory_entity + 一个 version + 一个 provenance，返回 entityId。 */
    private String 造一个带版本和溯源的实体(String spaceId, String scope) {
        String entityId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String provenanceId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO memory_entities (
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    first_seen_at, last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'PERSON', '张三', '张三', ?, ?, ?, ?)
                """, entityId, spaceId, scope, now.toString(), now.toString(),
                now.toString(), now.toString());

        jdbcTemplate.update("""
                INSERT INTO memory_entity_versions (
                    id, entity_id, version_no, valid_from, created_at, updated_at
                ) VALUES (?, ?, 1, ?, ?, ?)
                """, versionId, entityId, now.toString(), now.toString(), now.toString());

        jdbcTemplate.update("""
                INSERT INTO memory_entity_provenances (
                    id, entity_id, version_id, created_at
                ) VALUES (?, ?, ?, ?)
                """, provenanceId, entityId, versionId, now.toString());

        return entityId;
    }

    /** 在 space 下造一个 memory_relation + 一个 version。 */
    private void 造一个关系(String spaceId, String sourceEntityId, String targetEntityId) {
        String relationId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO memory_relations (
                    id, space_id, source_entity_id, target_entity_id, relation_type,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'KNOWS', ?, ?)
                """, relationId, spaceId, sourceEntityId, targetEntityId,
                now.toString(), now.toString());

        jdbcTemplate.update("""
                INSERT INTO memory_relation_versions (
                    id, relation_id, version_no, valid_from, created_at, updated_at
                ) VALUES (?, ?, 1, ?, ?, ?)
                """, versionId, relationId, now.toString(), now.toString(), now.toString());
    }

    private int 数行(String sql, Object... params) {
        Integer c = jdbcTemplate.queryForObject(sql, Integer.class, params);
        return c != null ? c : 0;
    }

    @Test
    void 删除项目_级联清空项目下所有会话() {
        Project project = 创建一个项目("论文项目");

        // 建两个归属此项目的会话 + 各一个 chat_turn + 一个 transcript entry
        String s1 = UUID.randomUUID().toString();
        String s2 = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessionStoreRepository.save(new ChatSession(s1, "论文对话1", null, 0, false, false,
                null, now, now, project.id()));
        sessionStoreRepository.save(new ChatSession(s2, "论文对话2", null, 0, false, false,
                null, now, now, project.id()));
        jdbcTemplate.update("""
                INSERT INTO chat_turns (turn_id, session_id, last_action, status, created_at, updated_at)
                VALUES (?, ?, 'SEND', 'COMPLETED', ?, ?)
                """, "turn-1", s1, now.toString(), now.toString());
        jdbcTemplate.update("""
                INSERT INTO session_transcript_entries (id, session_id, entry_type, created_at)
                VALUES (?, ?, 'user_message', ?)
                """, "entry-1", s1, now.toString());

        assertThat(数行("SELECT COUNT(*) FROM session_store WHERE project_id = ?", project.id()))
                .isEqualTo(2);
        assertThat(数行("SELECT COUNT(*) FROM chat_turns WHERE session_id = ?", s1)).isEqualTo(1);
        assertThat(数行("SELECT COUNT(*) FROM session_transcript_entries WHERE session_id = ?", s1))
                .isEqualTo(1);

        service.deleteProject(project.id());

        // session_store 清空 → FK CASCADE 带走 chat_turns / session_transcript_entries
        assertThat(数行("SELECT COUNT(*) FROM session_store WHERE project_id = ?", project.id()))
                .isZero();
        assertThat(数行("SELECT COUNT(*) FROM chat_turns WHERE session_id = ?", s1)).isZero();
        assertThat(数行("SELECT COUNT(*) FROM session_transcript_entries WHERE session_id = ?", s1))
                .isZero();
    }

    @Test
    void 删除项目_级联清空项目space下的记忆实体和关系() {
        Project project = 创建一个项目("知识项目");
        String spaceId = project.memorySpaceId();

        // 在项目 space 下造 2 个实体 + 1 个关系（关系指向两个实体）
        String e1 = 造一个带版本和溯源的实体(spaceId, "PROJECT");
        String e2 = 造一个带版本和溯源的实体(spaceId, "PROJECT");
        造一个关系(spaceId, e1, e2);

        assertThat(数行("SELECT COUNT(*) FROM memory_entities WHERE space_id = ?", spaceId))
                .isEqualTo(2);
        assertThat(数行("SELECT COUNT(*) FROM memory_entity_versions WHERE entity_id IN (?, ?)", e1, e2))
                .isEqualTo(2);
        assertThat(数行("SELECT COUNT(*) FROM memory_entity_provenances WHERE entity_id IN (?, ?)", e1, e2))
                .isEqualTo(2);
        assertThat(数行("SELECT COUNT(*) FROM memory_relations WHERE space_id = ?", spaceId))
                .isEqualTo(1);

        service.deleteProject(project.id());

        // 空间级联清理后：实体 / 关系 / 各自的 version / provenance 全部空
        assertThat(数行("SELECT COUNT(*) FROM memory_entities WHERE space_id = ?", spaceId)).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_entity_versions WHERE entity_id IN (?, ?)", e1, e2))
                .isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_entity_provenances WHERE entity_id IN (?, ?)", e1, e2))
                .isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_relations WHERE space_id = ?", spaceId)).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_relation_versions")).isZero();
        // 项目和 space 本身也删了
        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", project.id())).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", spaceId)).isZero();
    }

    @Test
    void 删除项目_不影响主账户的会话和记忆() {
        // 项目 A：会话 s-a + 一个实体 e-a
        Project projectA = 创建一个项目("项目A");
        String sA = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessionStoreRepository.save(new ChatSession(sA, "A的对话", null, 0, false, false,
                null, now, now, projectA.id()));
        String eA = 造一个带版本和溯源的实体(projectA.memorySpaceId(), "PROJECT");

        // 主账户：独立建一个 personal space + 一个主账户会话 + 一个主账户下的实体
        MemorySpace personalSpace = memorySpaceRepository.ensureDefaultPersonalSpace();
        String sMain = UUID.randomUUID().toString();
        sessionStoreRepository.save(new ChatSession(sMain, "主账户对话", null, 0, false, false,
                null, now, now, null));
        String eMain = 造一个带版本和溯源的实体(personalSpace.id(), "PERSONAL");

        service.deleteProject(projectA.id());

        // 项目 A 资源清空
        assertThat(数行("SELECT COUNT(*) FROM session_store WHERE session_id = ?", sA)).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_entities WHERE id = ?", eA)).isZero();
        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", projectA.id())).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", projectA.memorySpaceId()))
                .isZero();

        // 主账户资源原封不动
        assertThat(数行("SELECT COUNT(*) FROM session_store WHERE session_id = ?", sMain)).isEqualTo(1);
        assertThat(数行("SELECT COUNT(*) FROM memory_entities WHERE id = ?", eMain)).isEqualTo(1);
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", personalSpace.id()))
                .isEqualTo(1);
    }

    @Test
    void 删除项目_幂等性_第二次调用抛ProjectNotFoundException() {
        Project project = 创建一个项目("一次性项目");

        service.deleteProject(project.id());
        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", project.id())).isZero();

        // 再次删已删除的 id → 抛 ProjectNotFoundException（走 findById 的 orElseThrow）
        assertThatThrownBy(() -> service.deleteProject(project.id()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void 删除项目_memory_space_knowledge_bases通过FK_CASCADE自动清理() {
        // 补充验证 memory_space_* 关联表确实被 FK CASCADE 自动带走（Plan 原文关注点）
        Project project = 创建一个项目("挂KB的项目");
        String spaceId = project.memorySpaceId();
        Instant now = Instant.now();

        // 挂一个 memory_space_knowledge_bases 关联（knowledge_base 本身测试中无 FK 强约束，直接插）
        jdbcTemplate.update("""
                INSERT INTO memory_space_knowledge_bases (memory_space_id, knowledge_base_id, created_at)
                VALUES (?, ?, ?)
                """, spaceId, "kb-1", now.toString());
        assertThat(数行("SELECT COUNT(*) FROM memory_space_knowledge_bases WHERE memory_space_id = ?", spaceId))
                .isEqualTo(1);

        service.deleteProject(project.id());

        // FK CASCADE 自动带走关联
        assertThat(数行("SELECT COUNT(*) FROM memory_space_knowledge_bases WHERE memory_space_id = ?", spaceId))
                .isZero();
    }

    @Test
    void 删除项目_归属项目的会话在session_store为空时也不报错() {
        // 虽然单元测试已覆盖，但集成测试再走一遍真实 FK 路径，确保跳过 batchDelete 的分支没副作用
        Project project = 创建一个项目("空项目");
        assertThat(数行("SELECT COUNT(*) FROM session_store WHERE project_id = ?", project.id()))
                .isZero();

        service.deleteProject(project.id());

        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", project.id())).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", project.memorySpaceId()))
                .isZero();
    }

    @Test
    void 删除项目_只删tag含project的默认KB_保留用户手动挂的其他KB() {
        // 2026-04-24 新增：验证 Step 0 KB 级联删除的甄别逻辑
        //   场景：项目空间绑了 2 个 KB —— 一个是项目自动建的默认 KB（tag=["project"]），
        //         另一个是用户手动挂的通用资料库（tag=["shared"]）。
        //   期望：删项目只连带删项目默认 KB 本体，用户手动挂的保留。
        // 真实 KnowledgeBaseManager 依赖 6 个 repo + 向量索引，集成测试用 Mockito
        // mock 即可 —— 我们只关心"deleteKnowledgeBase 被以什么参数调了几次"。
        KnowledgeBaseManager kbManager = mock(KnowledgeBaseManager.class);
        ProjectService serviceWithKb = new ProjectService(
                projectRepository, memorySpaceRepository, sessionStoreRepository,
                jdbcTemplate, kbManager);

        Project project = 创建一个项目("挂两个KB的项目");
        String spaceId = project.memorySpaceId();
        Instant now = Instant.now();

        // 真实写入 memory_space_knowledge_bases（KB 本体表测试 schema 里没有，但 Service
        // 只通过 memorySpaceRepository 反查 id 列表 + kbManager 判 tag，不依赖 KB 表）
        jdbcTemplate.update("""
                INSERT INTO memory_space_knowledge_bases (memory_space_id, knowledge_base_id, created_at)
                VALUES (?, ?, ?)
                """, spaceId, "kb-project", now.toString());
        jdbcTemplate.update("""
                INSERT INTO memory_space_knowledge_bases (memory_space_id, knowledge_base_id, created_at)
                VALUES (?, ?, ?)
                """, spaceId, "kb-shared", now.toString());

        KnowledgeBase projectKb = new KnowledgeBase(
                "kb-project", "挂两个KB的项目 · 项目知识库", "自动建的默认 KB",
                null, null, "smart", Map.of(), 0, 0,
                List.of("project"), now, now, false, null, List.of());
        KnowledgeBase sharedKb = new KnowledgeBase(
                "kb-shared", "通用资料库", "用户手动挂的",
                null, null, "smart", Map.of(), 0, 0,
                List.of("shared", "handbook"), now, now, false, null, List.of());

        when(kbManager.getKnowledgeBase("kb-project")).thenReturn(Optional.of(projectKb));
        when(kbManager.getKnowledgeBase("kb-shared")).thenReturn(Optional.of(sharedKb));

        serviceWithKb.deleteProject(project.id());

        // tag 含 "project" → 被级联删
        verify(kbManager).deleteKnowledgeBase("kb-project");
        // tag=["shared","handbook"] 不含 "project" → 保留
        verify(kbManager, never()).deleteKnowledgeBase("kb-shared");

        // FK CASCADE 自动清 memory_space_knowledge_bases 两条关联（memory_space 删了）
        assertThat(数行(
                "SELECT COUNT(*) FROM memory_space_knowledge_bases WHERE memory_space_id = ?", spaceId))
                .isZero();
        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", project.id())).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", spaceId)).isZero();
    }

    @Test
    void 删除项目_KB本体缺失时_Step0跳过_主流程继续() {
        // 边界场景：memory_space_knowledge_bases 里有绑定但 getKnowledgeBase 返回 empty
        // （KB 本体已被别的路径先删了），Step 0 不抛 NPE，主流程继续到底。
        KnowledgeBaseManager kbManager = mock(KnowledgeBaseManager.class);
        ProjectService serviceWithKb = new ProjectService(
                projectRepository, memorySpaceRepository, sessionStoreRepository,
                jdbcTemplate, kbManager);

        Project project = 创建一个项目("KB已被先删的项目");
        String spaceId = project.memorySpaceId();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO memory_space_knowledge_bases (memory_space_id, knowledge_base_id, created_at)
                VALUES (?, ?, ?)
                """, spaceId, "kb-gone", now.toString());

        when(kbManager.getKnowledgeBase("kb-gone")).thenReturn(Optional.empty());

        serviceWithKb.deleteProject(project.id());

        verify(kbManager, never()).deleteKnowledgeBase(anyString());
        assertThat(数行("SELECT COUNT(*) FROM projects WHERE id = ?", project.id())).isZero();
        assertThat(数行("SELECT COUNT(*) FROM memory_spaces WHERE id = ?", spaceId)).isZero();
    }
}
