package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tier 1 工具 ID 聚合服务：配置 pinned + Advisory APPROVED。
 *
 * <p>{@link #getCurrentTier1Ids()} 的结果用于 ToolBridge 过滤可见集合，
 * 以及 tools.search 排除搜索池。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1Service {

    private final ToolConfigProperties config;
    private final Tier1AdvisoryRepository advisoryRepository;

    public Tier1Service(ToolConfigProperties config, Tier1AdvisoryRepository advisoryRepository) {
        this.config = config;
        this.advisoryRepository = advisoryRepository;
    }

    /** 当前 Tier 1 工具 ID 全集（pinned ∪ APPROVED）。 */
    public Set<String> getCurrentTier1Ids() {
        Set<String> ids = new LinkedHashSet<>(config.getTier1().getPinned());
        ids.addAll(advisoryRepository.findApprovedToolIds());
        return ids;
    }

    /** 判断工具是否在 pinned 列表（用于降级保护）。 */
    public boolean isPinned(String toolId) {
        return config.getTier1().getPinned().contains(toolId);
    }
}
