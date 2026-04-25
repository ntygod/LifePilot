package com.lifepilot.tool.search;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

/**
 * 启动时扫描 DynamicToolRegistry 并批量 INSERT 到 tool_search_index。
 *
 * <p>在 {@link com.lifepilot.tool.registry.BuiltinToolRegistrar} 之后执行
 * （通过 {@code @Order} 靠后），确保内置工具已注册完毕。Tier 1 工具和 meta 工具
 * 也会进索引（简化实现；搜索时按需过滤）。</p>
 *
 * <p>FTS5 索引用 {@code trigram} tokenizer：3 字符滑窗对中文 query
 * 做 substring 匹配（参见 {@link ToolSearchQuerySanitizer}）。配合规约：
 * 工具 description / tags 应把高频用户 query 短语显式包含在内。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndexBuilder.class);

    private final DynamicToolRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public ToolSearchIndexBuilder(DynamicToolRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动完成后全量构建索引。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void build() {
        log.info("开始构建工具搜索索引");
        jdbcTemplate.update("DELETE FROM tool_search_index");
        int inserted = 0;
        for (ToolContract tool : registry.getToolSnapshot()) {
            try {
                upsert(tool);
                inserted++;
            } catch (Exception e) {
                log.error("索引工具失败: toolId={}", tool.id(), e);
            }
        }
        log.info("工具搜索索引构建完成: count={}", inserted);
    }

    /**
     * 上插一条索引（FTS5 没有原生 UPSERT，用 DELETE + INSERT 实现）。
     *
     * @param tool 待索引工具
     */
    public void upsert(ToolContract tool) {
        jdbcTemplate.update("DELETE FROM tool_search_index WHERE tool_id = ?", tool.id());
        jdbcTemplate.update(
                "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) VALUES (?, ?, ?, ?, ?)",
                tool.id(),
                tool.description() == null ? "" : tool.description(),
                String.join(" ", tool.tags() == null ? Set.of() : tool.tags()),
                extractActions(tool),
                tool.category() == null ? "" : tool.category().name()
        );
    }

    /**
     * 删除一条索引。
     *
     * @param toolId 工具 ID
     */
    public void delete(String toolId) {
        jdbcTemplate.update("DELETE FROM tool_search_index WHERE tool_id = ?", toolId);
    }

    private String extractActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin) || builtin.actionMetadata() == null) {
            return "";
        }
        // ActionMetadata 不含 description，只索引 action 名（keys）
        return String.join(" ", builtin.actionMetadata().keySet());
    }
}
