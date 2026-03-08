package com.lifepilot.multiagent.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Agent 来源密封接口。
 *
 * <p>区分内置预设（Builtin）和用户 Markdown 定义（MarkdownDefined）两种来源。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface AgentSource permits AgentSource.Builtin, AgentSource.MarkdownDefined, AgentSource.Marketplace {

    /** 内置预设来源（JAR classpath 资源）。 */
    record Builtin() implements AgentSource {}

    /** 用户 Markdown 定义来源（文件系统 .md 文件）。 */
    record MarkdownDefined(String filePath, @Nullable Instant lastModified) implements AgentSource {}

    /** 市场安装来源（Extension Marketplace 安装）。 */
    record Marketplace(String packageId, String version) implements AgentSource {}
}

