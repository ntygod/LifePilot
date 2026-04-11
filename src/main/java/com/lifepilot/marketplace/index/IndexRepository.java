package com.lifepilot.marketplace.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引缓存 DAO — 基于 JdbcTemplate 操作 marketplace_index_cache 表。
 *
 * <p>提供索引缓存的 CRUD 操作，按 source_url 唯一键管理缓存条目。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class IndexRepository {

    private static final Logger log = LoggerFactory.getLogger(IndexRepository.class);

    private static final RowMapper<IndexCacheEntry> ROW_MAPPER = (rs, rowNum) -> new IndexCacheEntry(
            rs.getString("id"),
            rs.getString("source_url"),
            rs.getString("index_json"),
            rs.getString("fetched_at"),
            rs.getString("created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public IndexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存或更新索引缓存条目（按 source_url 做 UPSERT）。
     *
     * @param sourceUrl 索引源 URL
     * @param indexJson 索引 JSON 内容
     */
    public void save(String sourceUrl, String indexJson) {
        String now = Instant.now().toString();
        // SQLite UPSERT：source_url 有 UNIQUE 约束
        int updated = jdbcTemplate.update("""
                UPDATE marketplace_index_cache
                SET index_json = ?, fetched_at = ?
                WHERE source_url = ?
                """, indexJson, now, sourceUrl);

        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO marketplace_index_cache (id, source_url, index_json, fetched_at, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), sourceUrl, indexJson, now, now);
        }
        log.info("索引缓存保存成功: sourceUrl={}", sourceUrl);
    }

    /**
     * 按索引源 URL 查找缓存条目。
     *
     * @param sourceUrl 索引源 URL
     * @return 缓存条目 Optional，不存在时返回 empty
     */
    public Optional<IndexCacheEntry> findBySourceUrl(String sourceUrl) {
        List<IndexCacheEntry> results = jdbcTemplate.query(
                "SELECT id, source_url, index_json, fetched_at, created_at FROM marketplace_index_cache WHERE source_url = ?",
                ROW_MAPPER, sourceUrl);
        return results.stream().findFirst();
    }

    /**
     * 查询所有索引缓存条目。
     *
     * @return 所有缓存条目列表
     */
    public List<IndexCacheEntry> findAll() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT id, source_url, index_json, fetched_at, created_at FROM marketplace_index_cache ORDER BY created_at ASC",
                ROW_MAPPER));
    }

    /**
     * 按索引源 URL 删除缓存条目。
     *
     * @param sourceUrl 索引源 URL
     */
    public void deleteBySourceUrl(String sourceUrl) {
        jdbcTemplate.update("DELETE FROM marketplace_index_cache WHERE source_url = ?", sourceUrl);
        log.info("索引缓存删除成功: sourceUrl={}", sourceUrl);
    }

    /**
     * 索引缓存条目 — 对应 marketplace_index_cache 表的一行记录。
     *
     * @param id        记录 UUID
     * @param sourceUrl 索引源 URL
     * @param indexJson 索引 JSON 内容
     * @param fetchedAt 最后获取时间（ISO 8601）
     * @param createdAt 创建时间（ISO 8601）
     */
    public record IndexCacheEntry(
            String id,
            String sourceUrl,
            String indexJson,
            String fetchedAt,
            String createdAt
    ) {}
}
