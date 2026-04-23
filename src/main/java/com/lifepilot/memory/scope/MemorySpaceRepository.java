package com.lifepilot.memory.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆空间仓储。
 *
 * @author zsg
 * @since 2026-03-27
 */
@Repository
public class MemorySpaceRepository {

    private static final Logger log = LoggerFactory.getLogger(MemorySpaceRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemorySpaceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MemorySpace ensureDefaultPersonalSpace() {
        return ensureSpace(MemorySpaceKeys.defaultPersonal(), MemorySpaceType.PERSONAL, "个人记忆",
                "SYSTEM", "default", Map.of());
    }

    public MemorySpace ensureDefaultExperienceSpace() {
        return ensureSpace(MemorySpaceKeys.defaultExperience(), MemorySpaceType.EXPERIENCE, "Agent经验",
                "SYSTEM", "default", Map.of());
    }

    public MemorySpace ensureDatastoreDomainSpace(String datastoreId) {
        return ensureSpace(
                MemorySpaceKeys.datastoreDomain(datastoreId),
                MemorySpaceType.DOMAIN,
                "Datastore领域记忆",
                "DATASTORE",
                datastoreId,
                Map.of("datastoreId", datastoreId)
        );
    }

    public MemorySpace ensureKnowledgeBaseDomainSpace(String knowledgeBaseId) {
        return ensureSpace(
                MemorySpaceKeys.knowledgeBaseDomain(knowledgeBaseId),
                MemorySpaceType.DOMAIN,
                "知识库领域记忆",
                "KNOWLEDGE_BASE",
                knowledgeBaseId,
                Map.of("knowledgeBaseId", knowledgeBaseId)
        );
    }

    /**
     * 确保给定项目的项目级记忆空间存在（Plan 1 引入）。
     *
     * @param projectId 项目 id
     * @return 已存在或新建的项目记忆空间
     */
    public MemorySpace ensureProjectSpace(String projectId) {
        return ensureSpace(
                MemorySpaceKeys.project(projectId),
                MemorySpaceType.PROJECT,
                "项目记忆",
                "PROJECT",
                projectId,
                Map.of("projectId", projectId)
        );
    }

    /**
     * 按 id 物理删除记忆空间。
     *
     * <p>项目归档/删除时由调用方触发，不处理级联。</p>
     */
    public void deleteById(String spaceId) {
        jdbcTemplate.update("DELETE FROM memory_spaces WHERE id = ?", spaceId);
    }

    public MemorySpace ensureSpace(String spaceKey,
                                   MemorySpaceType spaceType,
                                   String displayName,
                                   @Nullable String ownerType,
                                   @Nullable String ownerId,
                                   @Nullable Map<String, Object> metadata) {
        return findBySpaceKey(spaceKey).orElseGet(() -> insertSpace(
                spaceKey,
                spaceType,
                displayName,
                ownerType,
                ownerId,
                metadata != null ? metadata : Map.of()
        ));
    }

    public Optional<MemorySpace> findBySpaceKey(String spaceKey) {
        List<MemorySpace> rows = jdbcTemplate.query("""
                SELECT id, space_key, space_type, display_name, owner_type, owner_id,
                       metadata_json, created_at, updated_at
                FROM memory_spaces
                WHERE space_key = ?
                """, this::mapRow, spaceKey);
        return rows.stream().findFirst();
    }

    public Optional<MemorySpace> findById(String id) {
        List<MemorySpace> rows = jdbcTemplate.query("""
                SELECT id, space_key, space_type, display_name, owner_type, owner_id,
                       metadata_json, created_at, updated_at
                FROM memory_spaces
                WHERE id = ?
                """, this::mapRow, id);
        return rows.stream().findFirst();
    }

    private MemorySpace insertSpace(String spaceKey,
                                    MemorySpaceType spaceType,
                                    String displayName,
                                    @Nullable String ownerType,
                                    @Nullable String ownerId,
                                    Map<String, Object> metadata) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO memory_spaces (
                    id, space_key, space_type, display_name, owner_type, owner_id,
                    metadata_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                spaceKey,
                spaceType.name(),
                displayName,
                ownerType,
                ownerId,
                writeJson(metadata),
                now.toString(),
                now.toString()
        );
        log.debug("创建记忆空间: id={}, spaceKey={}, spaceType={}", id, spaceKey, spaceType);
        return new MemorySpace(id, spaceKey, spaceType, displayName, ownerType, ownerId, metadata, now, now);
    }

    private MemorySpace mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MemorySpace(
                rs.getString("id"),
                rs.getString("space_key"),
                MemorySpaceType.valueOf(rs.getString("space_type")),
                rs.getString("display_name"),
                rs.getString("owner_type"),
                rs.getString("owner_id"),
                readJsonMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private Map<String, Object> readJsonMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("解析记忆空间 metadata_json 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化记忆空间 metadata_json 失败", e);
        }
    }
}
