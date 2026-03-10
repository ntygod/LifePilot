package com.lifepilot.interaction.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * A2UI（Agent-to-UI）功能配置属性。
 *
 * <p>绑定 {@code lifepilot.a2ui} 配置前缀。控制 A2UI 生成功能的启用状态和组件树限制。</p>
 *
 * @author zsg
 * @since 2026-03-11
 */
@ConfigurationProperties(prefix = "lifepilot.a2ui")
public record A2uiProperties(
        /** A2UI 功能总开关，默认启用 */
        @DefaultValue("true") boolean enabled,
        /** 单个组件树最大组件数量，默认 50 */
        @DefaultValue("50") int maxComponentsPerTree
) {}
