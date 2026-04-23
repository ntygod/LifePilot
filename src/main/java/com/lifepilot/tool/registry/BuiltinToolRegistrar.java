package com.lifepilot.tool.registry;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.validation.ToolValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * 内置工具自动注册器。
 *
 * <p>在 Spring 应用启动完成后，自动扫描所有 BuiltinTool Bean，
 * 先经 {@link ToolValidator} 校验命名规范，再注册到 {@link DynamicToolRegistry}。
 * 校验失败抛 {@link IllegalStateException} 阻止启动。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class BuiltinToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistrar.class);

    private final List<BuiltinTool> builtinTools;
    private final DynamicToolRegistry registry;
    private final ToolValidator validator;

    public BuiltinToolRegistrar(
            List<BuiltinTool> builtinTools,
            DynamicToolRegistry registry,
            ToolValidator validator) {
        this.builtinTools = builtinTools;
        this.registry = registry;
        this.validator = validator;
    }

    /**
     * 应用启动完成后校验并注册所有内置工具。
     *
     * @throws IllegalStateException 任一工具未通过 ToolValidator 校验
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        log.info("开始校验并注册内置工具: count={}", builtinTools.size());
        for (BuiltinTool tool : builtinTools) {
            validator.validate(tool);
            registry.registerBuiltinTool(tool);
        }
        log.info("内置工具注册完成: count={}", builtinTools.size());
        var counts = registry.getToolCountByLayer();
        log.info("工具注册统计: {}", counts);
    }
}
