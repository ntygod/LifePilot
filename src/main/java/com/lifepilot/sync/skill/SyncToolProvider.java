package com.lifepilot.sync.skill;

import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据同步工具提供者 — 注册同步相关工具到 DynamicToolRegistry。
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@code builtin.sync.trigger} — 触发数据同步</li>
 *   <li>{@code builtin.sync.status} — 查询同步状态</li>
 *   <li>{@code builtin.sync.config} — 管理同步配置</li>
 *   <li>{@code builtin.sync.conflicts} — 解决同步冲突</li>
 * </ul>
 *
 * <p>注意：同步模块尚未实现，工具将在同步基础设施就绪后补充。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class SyncToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SyncToolProvider.class);

    /**
     * 注册同步工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry toolRegistry) {
        // TODO: 同步模块基础设施就绪后实现工具注册
        log.info("同步工具提供者已初始化（工具待实现）");
    }
}
