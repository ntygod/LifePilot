package com.lifepilot.interaction.config;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内建渠道目录。
 *
 * <p>当前仅注册 Web UI 本地渠道，作为后续统一控制面的第一号官方插件。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class BuiltinChannelCatalog {

    private BuiltinChannelCatalog() {
    }

    public static List<ChannelPluginDescriptor> builtinPlugins() {
        return List.of(webuiPlugin());
    }

    public static ChannelPluginDescriptor webuiPlugin() {
        return new ChannelPluginDescriptor(
                "webui",
                "Web UI",
                "1.0.0",
                "zhiwei",
                "web",
                ConnectorMode.LOCAL,
                Map.of("transport", "http+sse"),
                List.of("receive", "send", "sse-stream", "a2ui-signal"),
                Map.of(
                        "type", "object",
                        "properties", Map.of()
                ),
                List.of(),
                Map.of(
                        "title", "Web UI 内建渠道",
                        "steps", List.of("该渠道为系统内建本地渠道，无需额外配置。")
                ),
                null
        );
    }

    public static ChannelInstance webDefaultInstance() {
        Instant now = Instant.now();
        return new ChannelInstance(
                "web.default",
                "webui",
                "web",
                "默认 Web UI",
                true,
                ChannelInstanceStatus.CREATED,
                Map.of("transport", "http+sse"),
                null,
                null,
                null,
                null,
                now,
                now
        );
    }
}
