package com.lifepilot.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import com.lifepilot.project.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 1 Task 25 —— 隔离语义端到端集成测试。
 *
 * <p>这是 Plan 1 最终验收测试的集中入口之二，目标是按 spec §12 的视角
 * 把 "项目 → ProjectContext 解析 → MemoryReadFilter 构造" 这条链
 * 在真实 MemorySpaceRepository + ProjectRepository 上完整走一遍，
 * 确认 ISOLATED / SHARED / 主账户三类情形产出的 filter 语义和 spec 一致。</p>
 *
 * <p>实现策略：沿用仓库既有的 SingleConnectionDataSource + 内存 SQLite
 * + 手动建表 pattern（见 {@code ProjectDeletion_级联集成测试}）。所有
 * 依赖的实体/行为都由 {@link ProjectContextResolver} 和
 * {@link MemoryReadFilter#buildForProject} 串成，避免 {@code @SpringBootTest}
 * 的完整上下文开销。</p>
 *
 * <p>对应 spec §12 验收点：
 * <ul>
 *   <li>验收 3（隔离项目，读方向）：filter spaceIds 包含 [projectSpace, personalSpace, experienceSpace]</li>
 *   <li>验收 4（不隔离项目）：filter spaceIds 只含 [personalSpace, experienceSpace]，双向合流</li>
 *   <li>验收 5（主账户 L3/L4 继承）：隔离项目 filter 始终包含主账户 personal/experience space</li>
 *   <li>主账户对话：ProjectContext.projectId / projectSpaceId 为 null；filter 只含两个主账户 space</li>
 * </ul>
 * Plan 1 先做 space-level 合并，key-level override（"项目内同名覆盖主账户"）
 * 留给后续 plan，不在本测试断言范围内。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProjectIsolation_端到端测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ProjectService projectService;
    private ProjectContextResolver resolver;
    private MemorySpaceRepository spaceRepo;
    private ProjectRepository projectRepo;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        createSchema();

        spaceRepo = new MemorySpaceRepository(jdbcTemplate, new ObjectMapper());
        projectRepo = new ProjectRepository(jdbcTemplate);
        SessionStoreRepository sessionRepo = new SessionStoreRepository(
                jdbcTemplate, new ObjectMapper(), null);
        // KnowledgeBaseManager = null：端到端验证项目隔离语义，不触达 KB 自动创建路径
        projectService = new ProjectService(projectRepo, spaceRepo, sessionRepo, jdbcTemplate, null);
        resolver = new ProjectContextResolver(projectRepo, spaceRepo);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

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

        // 删除路径会 DELETE 这两张表，即便表是空的也必须存在
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
    }

    @Test
    void 隔离项目的_ProjectContext_isolated为true_filter包含项目space和主账户两space() {
        Project p = projectService.createProject("隔离测试", "", ProjectIsolation.ISOLATED);

        ProjectContext ctx = resolver.resolve(p.id());

        assertThat(ctx.projectId()).isEqualTo(p.id());
        assertThat(ctx.projectSpaceId()).isEqualTo(p.memorySpaceId());
        assertThat(ctx.isolated()).isTrue();

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                ctx.projectSpaceId(), ctx.personalSpaceId(),
                ctx.experienceSpaceId(), ctx.isolated());

        // 验收 3 + 5：filter 含 [项目 space, 主账户 personal, 主账户 experience]
        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder(
                        ctx.projectSpaceId(),
                        ctx.personalSpaceId(),
                        ctx.experienceSpaceId())
                .hasSize(3);
    }

    @Test
    void 不隔离项目_filter不包含项目space_只有主账户两space() {
        Project p = projectService.createProject("共享测试", "", ProjectIsolation.SHARED);

        ProjectContext ctx = resolver.resolve(p.id());

        assertThat(ctx.projectId()).isEqualTo(p.id());
        assertThat(ctx.projectSpaceId()).isEqualTo(p.memorySpaceId());
        assertThat(ctx.isolated()).isFalse();

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                ctx.projectSpaceId(), ctx.personalSpaceId(),
                ctx.experienceSpaceId(), ctx.isolated());

        // 验收 4：不隔离项目只读主账户 space（项目 space 即便存在也不进 filter，体现"合流"语义）
        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder(ctx.personalSpaceId(), ctx.experienceSpaceId())
                .hasSize(2);
        assertThat(filter.spaceIds()).doesNotContain(ctx.projectSpaceId());
    }

    @Test
    void 主账户对话_ProjectContext_projectId为null_filter只含主账户两space() {
        // 不建项目，直接走 projectId=null 的主账户上下文
        ProjectContext ctx = resolver.resolve(null);

        assertThat(ctx.projectId()).isNull();
        assertThat(ctx.projectSpaceId()).isNull();
        assertThat(ctx.isolated()).isFalse();

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                null, ctx.personalSpaceId(), ctx.experienceSpaceId(), false);

        // 主账户对话只读主账户两个 space
        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder(ctx.personalSpaceId(), ctx.experienceSpaceId())
                .hasSize(2);
    }

    @Test
    void ensureDefault主账户space幂等_被所有项目共享_主账户L3L4被继承到所有项目的基础() {
        // 建两个隔离项目，它们各自解析出的 personalSpaceId / experienceSpaceId 应相同
        Project isoA = projectService.createProject("项目A", "", ProjectIsolation.ISOLATED);
        Project isoB = projectService.createProject("项目B", "", ProjectIsolation.ISOLATED);

        ProjectContext ctxA = resolver.resolve(isoA.id());
        ProjectContext ctxB = resolver.resolve(isoB.id());

        // 主账户 personal / experience space 是所有项目共享的唯一实例
        assertThat(ctxA.personalSpaceId()).isEqualTo(ctxB.personalSpaceId());
        assertThat(ctxA.experienceSpaceId()).isEqualTo(ctxB.experienceSpaceId());

        // 而每个项目的 projectSpace 是独立的
        assertThat(ctxA.projectSpaceId()).isNotEqualTo(ctxB.projectSpaceId());

        // 两个项目的 filter 各自包含自己的项目 space + 共享的主账户 space
        MemoryReadFilter fa = MemoryReadFilter.buildForProject(
                ctxA.projectSpaceId(), ctxA.personalSpaceId(),
                ctxA.experienceSpaceId(), true);
        MemoryReadFilter fb = MemoryReadFilter.buildForProject(
                ctxB.projectSpaceId(), ctxB.personalSpaceId(),
                ctxB.experienceSpaceId(), true);

        assertThat(fa.spaceIds()).contains(ctxA.personalSpaceId());  // 主账户 personal 被 A 读
        assertThat(fb.spaceIds()).contains(ctxB.personalSpaceId());  // 主账户 personal 被 B 读
        assertThat(fa.spaceIds()).contains(ctxA.projectSpaceId());
        assertThat(fa.spaceIds()).doesNotContain(ctxB.projectSpaceId());  // 项目 A 不读项目 B 的 space
    }

    @Test
    void 隔离项目删除后_主账户space不受影响_主账户对话仍可正常解析() {
        // 先让主账户 space 懒初始化（通过解析一次主账户 ctx）
        ProjectContext beforeCtx = resolver.resolve(null);
        MemorySpace personalBefore = spaceRepo.findById(beforeCtx.personalSpaceId()).orElseThrow();

        // 建一个隔离项目再删
        Project iso = projectService.createProject("即将删除", "", ProjectIsolation.ISOLATED);
        String projectSpaceId = iso.memorySpaceId();
        projectService.deleteProject(iso.id());

        // 主账户 space 依然存在且 id 未变
        assertThat(spaceRepo.findById(beforeCtx.personalSpaceId())).isPresent();
        MemorySpace personalAfter = spaceRepo.findById(beforeCtx.personalSpaceId()).orElseThrow();
        assertThat(personalAfter.id()).isEqualTo(personalBefore.id());

        // 项目 space 已随删除清掉
        assertThat(spaceRepo.findById(projectSpaceId)).isEmpty();

        // 主账户对话再解析一次，仍拿到同一份主账户 space
        ProjectContext afterCtx = resolver.resolve(null);
        assertThat(afterCtx.personalSpaceId()).isEqualTo(beforeCtx.personalSpaceId());
        assertThat(afterCtx.experienceSpaceId()).isEqualTo(beforeCtx.experienceSpaceId());
    }
}
