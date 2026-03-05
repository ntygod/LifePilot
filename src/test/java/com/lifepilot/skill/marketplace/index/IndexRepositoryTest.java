package com.lifepilot.skill.marketplace.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IndexRepository 单元测试。
 *
 * <p>使用内存 SQLite 直接构建 JdbcTemplate，无需启动 Spring 上下文。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class IndexRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private IndexRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 创建表结构
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS marketplace_index_cache (
                    id          TEXT PRIMARY KEY,
                    source_url  TEXT NOT NULL UNIQUE,
                    index_json  TEXT NOT NULL,
                    fetched_at  TEXT NOT NULL,
                    created_at  TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("DELETE FROM marketplace_index_cache");
        repository = new IndexRepository(jdbcTemplate);
    }

    @Test
    void save_findBySourceUrl_往返一致() {
        String sourceUrl = "https://example.com/index.json";
        String indexJson = "[{\"id\":\"skill-1\",\"name\":\"测试技能\"}]";

        repository.save(sourceUrl, indexJson);
        var found = repository.findBySourceUrl(sourceUrl);

        assertThat(found).isPresent();
        var entry = found.get();
        assertThat(entry.sourceUrl()).isEqualTo(sourceUrl);
        assertThat(entry.indexJson()).isEqualTo(indexJson);
        assertThat(entry.id()).isNotBlank();
        assertThat(entry.fetchedAt()).isNotBlank();
        assertThat(entry.createdAt()).isNotBlank();
    }

    @Test
    void save_相同sourceUrl_更新indexJson() {
        String sourceUrl = "https://example.com/index.json";
        repository.save(sourceUrl, "[{\"id\":\"v1\"}]");

        var first = repository.findBySourceUrl(sourceUrl).orElseThrow();
        String originalCreatedAt = first.createdAt();

        repository.save(sourceUrl, "[{\"id\":\"v2\"}]");

        var updated = repository.findBySourceUrl(sourceUrl).orElseThrow();
        assertThat(updated.indexJson()).isEqualTo("[{\"id\":\"v2\"}]");
        assertThat(updated.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.id()).isEqualTo(first.id());
    }

    @Test
    void findBySourceUrl_不存在_返回empty() {
        assertThat(repository.findBySourceUrl("https://nonexistent.com/index.json")).isEmpty();
    }

    @Test
    void findAll_返回所有缓存条目() {
        repository.save("https://source-a.com/index.json", "[]");
        repository.save("https://source-b.com/index.json", "[]");
        repository.save("https://source-c.com/index.json", "[]");

        var all = repository.findAll();
        assertThat(all).hasSize(3);
    }

    @Test
    void findAll_空表_返回空列表() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_返回不可变列表() {
        repository.save("https://example.com/index.json", "[]");
        var all = repository.findAll();
        assertThat(all).isUnmodifiable();
    }

    @Test
    void deleteBySourceUrl_删除已有条目() {
        String sourceUrl = "https://example.com/index.json";
        repository.save(sourceUrl, "[]");
        assertThat(repository.findBySourceUrl(sourceUrl)).isPresent();

        repository.deleteBySourceUrl(sourceUrl);
        assertThat(repository.findBySourceUrl(sourceUrl)).isEmpty();
    }

    @Test
    void deleteBySourceUrl_不存在的url_不抛异常() {
        repository.deleteBySourceUrl("https://nonexistent.com/index.json");
    }

    @Test
    void deleteBySourceUrl_不影响其他条目() {
        repository.save("https://source-a.com/index.json", "[\"a\"]");
        repository.save("https://source-b.com/index.json", "[\"b\"]");

        repository.deleteBySourceUrl("https://source-a.com/index.json");

        assertThat(repository.findBySourceUrl("https://source-a.com/index.json")).isEmpty();
        assertThat(repository.findBySourceUrl("https://source-b.com/index.json")).isPresent();
        assertThat(repository.findAll()).hasSize(1);
    }
}
