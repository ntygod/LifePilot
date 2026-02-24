package com.lifepilot.tool.registry;

import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * 内置工具自动注册器。
 *
 * <p>在 Spring 应用启动完成后，自动扫描所有 BuiltinTool Bean
 * 并注册到 DynamicToolRegistry。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class BuiltinToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistrar.class);

    private final List<BuiltinTool> builtinTools;
    private final DynamicToolRegistry registry;

    public BuiltinToolRegistrar(
            List<BuiltinTool> builtinTools,
            DynamicToolRegistry registry) {
        this.builtinTools = builtinTools;
        this.registry = registry;
    }

    /**
     * 应用启动完成后自动注册所有内置工具。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        log.info("开始注册内置工具: count={}", builtinTools.size());
        for (BuiltinTool tool : builtinTools) {
            registry.registerBuiltinTool(tool);
        }
        log.info("内置工具注册完成: count={}", builtinTools.size());
        var counts = registry.getToolCountByLayer();
        log.info("工具注册统计: {}", counts);
    }
}
