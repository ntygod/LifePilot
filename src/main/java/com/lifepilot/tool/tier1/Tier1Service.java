package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tier 1 工具 ID 提供服务，只读 {@code lifepilot.tool.tier1.pinned} 配置。
 *
 * <p>{@link #getCurrentTier1Ids()} 用于 ToolBridge 过滤可见集合，
 * 以及 {@code tools.search} 排除搜索池。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1Service {

    private final ToolConfigProperties config;

    public Tier1Service(ToolConfigProperties config) {
        this.config = config;
    }

    /** 当前 Tier 1 工具 ID 全集（来自 pinned 配置）。 */
    public Set<String> getCurrentTier1Ids() {
        return new LinkedHashSet<>(config.getTier1().getPinned());
    }

    /** 判断工具是否在 pinned 列表（用于降级保护）。 */
    public boolean isPinned(String toolId) {
        return config.getTier1().getPinned().contains(toolId);
    }
}
