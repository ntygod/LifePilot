package com.lifepilot.memory.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticMemory 实体版本集成测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@DisplayName("SemanticMemory 实体版本集成测试")
class SemanticMemory_实体版本集成测试 {

    private JdbcTemplate jdbcTemplate;
    private ConflictDetector conflictDetector;
    private VectorSearcher vectorSearcher;
    private SemanticMemory semanticMemory;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

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
                )
                """);
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
                    lifecycle_state TEXT NOT NULL DEFAULT 'ACTIVE',
                    lifecycle_reason TEXT,
                    expires_at TEXT,
                    temporality TEXT NOT NULL DEFAULT 'PERSISTENT',
                    succeeded_by TEXT,
                    is_derived INTEGER NOT NULL DEFAULT 0,
                    derivation_sources TEXT,
                    evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                    trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED',
                    trust_score REAL NOT NULL DEFAULT 0.0,
                    evidence_count INTEGER NOT NULL DEFAULT 0,
                    last_verified_at TEXT
                )
                """);
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
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_entity_provenances (
                    id TEXT PRIMARY KEY,
                    entity_id TEXT NOT NULL,
                    version_id TEXT,
                    origin_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                    source_reference TEXT,
                    source_conversation_id TEXT,
                    source_session_id TEXT,
                    source_turn_id TEXT,
                    source_entry_id TEXT,
                    source_document_id TEXT,
                    source_knowledge_base_id TEXT,
                    evidence_excerpt TEXT,
                    evidence_hash TEXT,
                    confidence REAL NOT NULL DEFAULT 0.0,
                    evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                    trust_score REAL NOT NULL DEFAULT 0.0,
                    trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED',
                    created_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_projection_outbox (
                    id TEXT PRIMARY KEY,
                    aggregate_type TEXT NOT NULL,
                    aggregate_id TEXT NOT NULL,
                    projection_type TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TEXT,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    processed_at TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE VIEW temporal_entities AS
                SELECT
                    me.id AS id,
                    me.entity_type AS type,
                    me.canonical_name AS name,
                    mev.description AS description,
                    mev.properties_json AS properties_json,
                    mev.version_no AS version,
                    mev.is_current AS is_current,
                    mev.valid_from AS valid_from,
                    mev.valid_to AS valid_to,
                    p.source_conversation_id AS source_conversation_id,
                    mev.extraction_confidence AS extraction_confidence,
                    mev.importance_score AS importance_score,
                    me.access_count AS access_count,
                    me.last_accessed_at AS last_accessed_at,
                    me.created_at AS created_at,
                    mev.updated_at AS updated_at,
                    me.lifecycle_state AS lifecycle_state,
                    me.lifecycle_reason AS lifecycle_reason,
                    me.expires_at AS expires_at,
                    me.temporality AS temporality,
                    me.succeeded_by AS succeeded_by,
                    me.is_derived AS is_derived,
                    me.derivation_sources AS derivation_sources,
                    me.evidence_kind AS evidence_kind,
                    me.trust_level AS trust_level,
                    me.trust_score AS trust_score,
                    me.evidence_count AS evidence_count,
                    me.last_verified_at AS last_verified_at
                FROM memory_entities me
                JOIN memory_entity_versions mev ON mev.entity_id = me.id
                LEFT JOIN memory_entity_provenances p ON p.version_id = mev.id
                """);

        var objectMapper = new ObjectMapper();
        var memorySpaceRepository = new MemorySpaceRepository(jdbcTemplate, objectMapper);
        conflictDetector = mock(ConflictDetector.class);
        vectorSearcher = mock(VectorSearcher.class);
        var outboxRepository = new MemoryProjectionOutboxRepository(jdbcTemplate, objectMapper);
        var outboxProcessor = new MemoryProjectionOutboxProcessor(outboxRepository, vectorSearcher, objectMapper);
        var projectionService = new MemoryProjectionService(outboxRepository, outboxProcessor);
        semanticMemory = new SemanticMemory(
                jdbcTemplate,
                conflictDetector,
                new VersionMerger(),
                vectorSearcher,
                memorySpaceRepository,
                projectionService
        );
    }

    @Test
    void 同一逻辑实体更新时应保持稳定实体Id并写入版本与来源() {
        Instant now = Instant.parse("2026-03-27T03:00:00Z");
        var created = new TemporalEntity(
                "entity-hero",
                EntityType.PERSON,
                "林玄",
                "主角初始设定",
                Map.of("identity", "主角"),
                1,
                true,
                now,
                null,
                "session-1",
                0.8f,
                0.7f,
                0,
                null,
                now,
                now
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                now);
        when(conflictDetector.detectConflict(any(), nullable(String.class)))
                .thenReturn(Optional.empty(), Optional.of(created));

        var persisted = semanticMemory.upsertWithConflictDetection(
                created,
                "session-1",
                MemoryWriteContext.conversation("session-1"));
        var updated = semanticMemory.upsertWithConflictDetection(
                new TemporalEntity(
                        null,
                        EntityType.PERSON,
                        "林玄",
                        "主角，来自青岚城",
                        Map.of("identity", "主角", "city", "青岚城"),
                        1,
                        true,
                        now,
                        null,
                        "session-2",
                        0.9f,
                        0.85f,
                        0,
                        null,
                        now,
                        now
                ,
                        com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                        null,
                        null,
                        com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                        null,
                        false,
                        java.util.List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        now),
                "session-2",
                MemoryWriteContext.conversation("session-2")
        );

        assertThat(persisted.id()).isEqualTo("entity-hero");
        assertThat(updated.id()).isEqualTo("entity-hero");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.description()).isEqualTo("主角，来自青岚城");
        assertThat(updated.properties()).containsEntry("city", "青岚城");

        Integer entityCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_entities", Integer.class);
        Integer versionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_entity_versions WHERE entity_id = 'entity-hero'", Integer.class);
        Integer currentVersionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_entity_versions WHERE entity_id = 'entity-hero' AND is_current = 1", Integer.class);
        Integer provenanceCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memory_entity_provenances WHERE entity_id = 'entity-hero'", Integer.class);

        assertThat(entityCount).isEqualTo(1);
        assertThat(versionCount).isEqualTo(2);
        assertThat(currentVersionCount).isEqualTo(1);
        assertThat(provenanceCount).isEqualTo(2);

        String latestDescription = jdbcTemplate.queryForObject("""
                SELECT description
                FROM memory_entity_versions
                WHERE entity_id = 'entity-hero' AND is_current = 1
                """, String.class);
        assertThat(latestDescription).isEqualTo("主角，来自青岚城");

        verify(vectorSearcher).upsertEntityVector("entity-hero", persisted.textRepresentation());
        verify(vectorSearcher).upsertEntityVector("entity-hero", updated.textRepresentation());
    }

    @Test
    void countByEntityType_实体类型被污染时应失败() {
        String now = Instant.parse("2026-06-23T10:00:00Z").toString();
        jdbcTemplate.update("""
                INSERT INTO memory_entities (
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    first_seen_at, last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "entity-broken-type",
                "space-1",
                "USER_PROFILE",
                "BROKEN_TYPE",
                "污染实体",
                "污染实体",
                now,
                now,
                now,
                now);
        jdbcTemplate.update("""
                INSERT INTO memory_entity_versions (
                    id, entity_id, version_no, description, is_current,
                    valid_from, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "version-broken-type",
                "entity-broken-type",
                1,
                "实体类型被污染",
                1,
                now,
                now,
                now);

        assertThatThrownBy(() -> semanticMemory.countByEntityType(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROKEN_TYPE");
    }
}
