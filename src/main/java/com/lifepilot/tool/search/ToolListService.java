package com.lifepilot.tool.search;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code tools.list} 服务：按 category 返回工具 ID（不含 description 避免 token 膨胀）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolListService {

    private final DynamicToolRegistry registry;

    public ToolListService(DynamicToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 列出工具 ID，按 category 分组返回。
     *
     * @param category 可选 category 过滤（大小写不敏感），null/空表示全部
     * @return 分组结果（永不为 null）
     */
    public ToolListResult list(@Nullable String category) {
        List<ToolContract> all = registry.getToolSnapshot();
        Map<String, List<String>> grouped = all.stream()
                .filter(t -> t.category() != null)
                .filter(t -> category == null || category.isBlank()
                        || t.category().name().equalsIgnoreCase(category))
                .collect(Collectors.groupingBy(
                        t -> t.category().name(),
                        LinkedHashMap::new,
                        Collectors.mapping(ToolContract::id, Collectors.toList())));
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return new ToolListResult(grouped, total);
    }
}
