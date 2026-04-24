package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.InvalidationKind;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.lifecycle.events.SourceInvalidated;
import com.lifepilot.memory.lifecycle.feedback.RevalidationQueueRepository;
import com.lifepilot.memory.lifecycle.listeners.ProvenanceStaleListener;
import com.lifepilot.memory.lifecycle.listeners.ReValidationListener;
import com.lifepilot.memory.lifecycle.scanner.OrphanProvenanceScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 场景 S7：孤儿引用被标记陈旧 — Document 归档 / 物理删除后，引用它的
 * {@code memory_entity_provenances} 记录沿 VALID → STALE 转换，且受影响实体入
 * {@code memory_revalidation_queue} 的 PENDING 队列；后续 HybridRetriever 召回
 * 时会带 {@code needsRevalidation=true} 标注。
 *
 * <p>验证目标：
 * <ol>
 *   <li>{@link OrphanProvenanceScanner} 定位到 {@code session_documents} 中不存在
 *       的 "孤儿" doc ID，发一条 {@link SourceInvalidated}(DOCUMENT, id, DELETED)；</li>
 *   <li>{@link ProvenanceStaleListener} 将对应行的 {@code status} 置为 STALE
 *       （V15 新列）；</li>
 *   <li>{@link ReValidationListener} 把受影响实体 ID 入再验证队列 PENDING 行；</li>
 *   <li>仍然存在的 doc 对应 provenance 保持 VALID，不被误伤。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 @SpringBootTest 完整上下文（同 B16 降级原因 — {@code lifepilot.meta.enabled=false}
 *       + SkillTestSupport ComponentScan 污染）；</li>
 *   <li>改用 {@link org.flywaydb.core.Flyway} 真跑 + {@link SingleConnectionDataSource}
 *       文件 SQLite + 手工装配 Listener（构造器注入 repository & Clock）；</li>
 *   <li>{@link ProvenanceStaleListener} / {@link ReValidationListener} 都是
 *       {@code @TransactionalEventListener(AFTER_COMMIT)}，无 Spring 事务上下文下无法自动
 *       触发；本测试保留 {@code OrphanProvenanceScanner} 的真实 publisher，并注册同步监听器 lambda
 *       直接转发到两条 listener 的 public 方法，模拟 AFTER_COMMIT 语义；</li>
 *   <li>不调 HybridRetriever 断言 {@code needsRevalidation=true}（对应断言由
 *       {@code HybridRetriever_生命周期过滤_集成测试} 覆盖），本场景只断言闭环前两跳
 *       (OrphanScanner → ProvenanceStale/Revalidation) 的效果。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("场景 S7 孤儿引用被标记陈旧")
class 孤儿引用被标记陈旧_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-s7";
    private static final String SESSION_ID = "sess-s7";
    private static final String ENTITY_REAL_1 = "ent-real-1";
    private static final String ENTITY_REAL_2 = "ent-real-2";
    private static final String ENTITY_GHOST = "ent-ghost";
    private static final String DOC_GHOST = "doc-ghost";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private OrphanProvenanceScanner scanner;
    private MemoryProvenanceRepository provenanceRepo;
    private RevalidationQueueRepository revalidationQueueRepo;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s7-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        provenanceRepo = new MemoryProvenanceRepository(jdbcTemplate);
        var documentRepo = new SessionDocumentRepository(jdbcTemplate);
        revalidationQueueRepo = new RevalidationQueueRepository(jdbcTemplate);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        // 两条 listener 的同步转发：Scanner 发的事件 -> 两 listener 立即消费
        // 这里模拟 @TransactionalEventListener(AFTER_COMMIT) 的语义：单测无事务
        // 管理器，改用 lambda 直接调 listener 的 public 方法
        var staleListener = new ProvenanceStaleListener(provenanceRepo, clock);
        var reValidationListener = new ReValidationListener(
                provenanceRepo, revalidationQueueRepo, clock);

        ApplicationEventPublisher forwardingPublisher = event -> {
            if (event instanceof SourceInvalidated invalidated) {
                staleListener.onSourceInvalidated(invalidated);
                reValidationListener.onSourceInvalidated(invalidated);
            }
        };

        scanner = new OrphanProvenanceScanner(provenanceRepo, documentRepo, forwardingPublisher);

        插入记忆空间(SPACE_ID);
        插入会话(SESSION_ID);
        插入实体(ENTITY_REAL_1);
        插入实体(ENTITY_REAL_2);
        插入实体(ENTITY_GHOST);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    @DisplayName("孤儿 document 引用应转 STALE 并入再验证队列，真实 doc 保持 VALID")
    void 孤儿document引用应转STALE并入队_真实doc不受影响() {
        // 1. 一条真实存在的 doc + 一条指向真实 doc 的 provenance 保留 VALID 参考组
        String docReal = UUID.randomUUID().toString();
        插入Document(docReal);
        插入Provenance(ENTITY_REAL_1, docReal, "VALID");

        // 2. 两条 provenance 引用一个 ghost document（session_documents 里不存在）
        插入Provenance(ENTITY_GHOST, DOC_GHOST, "VALID");
        插入Provenance(ENTITY_REAL_2, DOC_GHOST, "VALID");

        // 3. 扫描 —— 发 SourceInvalidated(DOCUMENT, doc-ghost, DELETED)
        scanner.scanNow();

        // 4. 断言 provenance 状态：ghost 引用转 STALE，真实 doc 仍 VALID
        assertThat(读取Status(ENTITY_GHOST, DOC_GHOST))
                .as("孤儿 doc 引用应转 STALE")
                .isEqualTo("STALE");
        assertThat(读取Status(ENTITY_REAL_2, DOC_GHOST))
                .as("孤儿 doc 引用批量转 STALE，影响所有引用该 doc 的实体")
                .isEqualTo("STALE");
        assertThat(读取Status(ENTITY_REAL_1, docReal))
                .as("真实存在的 doc 引用保持 VALID，不被误伤")
                .isEqualTo("VALID");

        // 5. 再验证队列 —— 受影响的 2 个实体各入一条 PENDING
        assertThat(revalidationQueueRepo.countPendingBySource(SourceType.DOCUMENT, DOC_GHOST))
                .as("受影响实体入 PENDING 队列，待 HybridRetriever 命中时带 needsRevalidation=true")
                .isEqualTo(2);
        assertThat(读取队列实体ID集合(DOC_GHOST))
                .as("入队实体 ID 覆盖所有指向 ghost doc 的实体（去重）")
                .containsExactlyInAnyOrder(ENTITY_GHOST, ENTITY_REAL_2);
    }

    @Test
    @DisplayName("所有 document 都存在时 scanner 不发事件，provenance 保持 VALID")
    void 全部doc存在时不应标记STALE() {
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        插入Document(docA);
        插入Document(docB);
        插入Provenance(ENTITY_REAL_1, docA, "VALID");
        插入Provenance(ENTITY_REAL_2, docB, "VALID");

        scanner.scanNow();

        assertThat(读取Status(ENTITY_REAL_1, docA)).isEqualTo("VALID");
        assertThat(读取Status(ENTITY_REAL_2, docB)).isEqualTo("VALID");
        assertThat(revalidationQueueRepo.countPendingBySource(SourceType.DOCUMENT, docA))
                .as("全部 doc 存在时不入再验证队列")
                .isZero();
        assertThat(revalidationQueueRepo.countPendingBySource(SourceType.DOCUMENT, docB))
                .isZero();
    }

    // ---------- 测试夹具 ----------

    /** 读取 (entityId, documentId) provenance 行的 status 列。 */
    private String 读取Status(String entityId, String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM memory_entity_provenances "
                        + "WHERE entity_id = ? AND source_document_id = ?",
                String.class, entityId, documentId);
    }

    /** 读取再验证队列中针对某 sourceId 的所有 PENDING 行的 entity_id。 */
    private List<String> 读取队列实体ID集合(String sourceId) {
        return jdbcTemplate.queryForList(
                "SELECT entity_id FROM memory_revalidation_queue "
                        + "WHERE source_id = ? AND status = 'PENDING'",
                String.class, sourceId);
    }

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S7 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入会话(String sessionId) {
        jdbcTemplate.update(
                """
                INSERT INTO session_store(session_id, title, created_at, updated_at, last_activity_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                sessionId, "s7-test-session",
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入实体(String entityId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', 'CUSTOM', ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, 'ACTIVE', 'PERSISTENT', 0)
                """,
                entityId, SPACE_ID, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private ArrayList<String> 已插入Document = new ArrayList<>();

    private void 插入Document(String docId) {
        jdbcTemplate.update(
                """
                INSERT INTO session_documents(
                    id, session_id, entry_id, file_name, file_path,
                    file_size, mime_type, origin, created_at)
                VALUES (?, ?, NULL, 'test.txt', '/tmp/test.txt', 0, 'text/plain', 'MANUAL', ?)
                """,
                docId, SESSION_ID, FIXED_NOW.toString());
        已插入Document.add(docId);
    }

    private void 插入Provenance(String entityId, String documentId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, origin_type, source_document_id, status, created_at)
                VALUES (?, ?, 'DOCUMENT', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, documentId, status, FIXED_NOW.toString());
    }

    /** 无效事件确保编译器检查 InvalidationKind 枚举存在（未在 DSL 中调用时保留最小依赖）。 */
    @SuppressWarnings("unused")
    private static final InvalidationKind KIND_REF = InvalidationKind.DELETED;
}
