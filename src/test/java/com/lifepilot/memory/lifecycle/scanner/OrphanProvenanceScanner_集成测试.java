package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.InvalidationKind;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.lifecycle.events.SourceInvalidated;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrphanProvenanceScanner} 集成测试 —— Flyway 真跑迁移 + 真 Repository，
 * 插入 session_documents / memory_entities / memory_entity_provenances 骨架行，
 * 验证：
 * <ul>
 *   <li>存在的 document 不发事件</li>
 *   <li>孤儿 document 发 {@link SourceInvalidated}(DOCUMENT, id, DELETED)</li>
 *   <li>已 STALE 的 provenance 不重复触发</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("OrphanProvenanceScanner 集成测试")
class OrphanProvenanceScanner_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-orphan";
    private static final String SESSION_ID = "sess-orphan";
    private static final String ENTITY_ID = "ent-orphan";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private OrphanProvenanceScanner scanner;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-orphan-" + dbId + ".db");
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

        var provenanceRepo = new MemoryProvenanceRepository(jdbcTemplate);
        var documentRepo = new SessionDocumentRepository(jdbcTemplate);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;

        scanner = new OrphanProvenanceScanner(provenanceRepo, documentRepo, publisher);

        插入记忆空间(SPACE_ID);
        插入实体(ENTITY_ID);
        插入会话(SESSION_ID);
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
    void 孤儿document引用应发SourceInvalidated() {
        // 两条 provenance 指向存在的 doc-a，一条指向不存在的 doc-ghost
        String docA = UUID.randomUUID().toString();
        插入Document(docA);
        插入Provenance(docA, "VALID");
        插入Provenance(docA, "VALID");
        插入Provenance("doc-ghost", "VALID");

        scanner.scanNow();

        var invalidated = capturedInvalidated();
        assertThat(invalidated)
                .as("仅对孤儿 doc-ghost 发事件")
                .hasSize(1);
        assertThat(invalidated.getFirst().sourceType()).isEqualTo(SourceType.DOCUMENT);
        assertThat(invalidated.getFirst().sourceId()).isEqualTo("doc-ghost");
        assertThat(invalidated.getFirst().kind()).isEqualTo(InvalidationKind.DELETED);
    }

    @Test
    void 全部document都存在不发事件() {
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();
        插入Document(docA);
        插入Document(docB);
        插入Provenance(docA, "VALID");
        插入Provenance(docB, "VALID");

        scanner.scanNow();

        assertThat(capturedInvalidated())
                .as("全部 document 都在，不发事件")
                .isEmpty();
    }

    @Test
    void 已STALE的provenance不重复触发() {
        // 只有一条 VALID（doc-live 存在），另一条 STALE（doc-gone 不存在）
        String docLive = UUID.randomUUID().toString();
        插入Document(docLive);
        插入Provenance(docLive, "VALID");
        插入Provenance("doc-gone", "STALE");

        scanner.scanNow();

        assertThat(capturedInvalidated())
                .as("STALE 的 provenance 被 listDistinctSourceDocumentIds 过滤掉，不再触发")
                .isEmpty();
    }

    // ---------- 测试夹具 ----------

    private List<SourceInvalidated> capturedInvalidated() {
        return publishedEvents.stream()
                .filter(e -> e instanceof SourceInvalidated)
                .map(e -> (SourceInvalidated) e)
                .toList();
    }

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
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

    private void 插入会话(String sessionId) {
        jdbcTemplate.update(
                """
                INSERT INTO session_store(session_id, title, created_at, updated_at, last_activity_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                sessionId, "test-session",
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入Document(String docId) {
        jdbcTemplate.update(
                """
                INSERT INTO session_documents(
                    id, session_id, entry_id, file_name, file_path,
                    file_size, mime_type, origin, created_at)
                VALUES (?, ?, NULL, 'test.txt', '/tmp/test.txt', 0, 'text/plain', 'MANUAL', ?)
                """,
                docId, SESSION_ID, FIXED_NOW.toString());
    }

    private void 插入Provenance(String documentId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, origin_type, source_document_id, status, created_at)
                VALUES (?, ?, 'DOCUMENT', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), ENTITY_ID, documentId, status, FIXED_NOW.toString());
    }
}
