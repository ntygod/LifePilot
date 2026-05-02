package com.lifepilot.tool.validation;

import com.lifepilot.tool.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具命名规范启动期强校验器。
 *
 * <p>硬规则违反抛 {@link IllegalStateException} 阻止启动；
 * 软规则仅记录 warn 日志不阻塞。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolValidator.class);

    private static final Pattern ID_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static final Set<String> SELF_DESCRIPTIVE_NAMESPACES = Set.of(
            "memory", "knowledge", "notify", "shell", "web",
            "file", "document", "datastore", "cron", "channel",
            "process", "tools", "ui", "system",
            "git", "code", "workflow"
    );

    private static final Set<String> VERB_ROOTS = Set.of(
            "read", "write", "list", "create", "update", "delete", "remove",
            "query", "search", "find", "fetch", "get", "put", "post",
            "send", "emit", "notify", "exec", "execute", "run",
            "patch", "commit", "rollback", "store", "save", "load",
            "start", "stop", "restart", "cancel", "schedule", "trigger",
            "describe", "inspect", "status",
            "spawn", "generate", "browse"
    );

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final int MIN_DESCRIPTION_LENGTH = 20;
    private static final int MIN_TAG_COUNT = 3;

    private static final Set<String> EXEMPTED_IDS = Set.of(
            "tool.search", "tool.search"
    );

    /**
     * 不参与校验的 ID 前缀 — 外部生态工具（如 A2A 远端 agent、未来的 MCP 动态工具等）
     * 的 ID 规范由外部协议控制，项目校验器对其豁免。
     */
    private static final Set<String> EXEMPTED_ID_PREFIXES = Set.of(
            "a2a_remote_"
    );

    /**
     * 校验单个工具是否满足命名规范。
     *
     * @param tool 待校验工具
     * @throws IllegalStateException 任一硬规则违反
     */
    public void validate(ToolContract tool) {
        String id = tool.id();
        if (EXEMPTED_IDS.contains(id)) {
            return;
        }
        if (EXEMPTED_ID_PREFIXES.stream().anyMatch(id::startsWith)) {
            return;
        }
        validateId(tool);
        validateName(tool);
        validateDescription(tool);
        validateTags(tool);
        validateActions(tool);
    }

    private void validateId(ToolContract tool) {
        String id = tool.id();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalStateException(
                    "ID 格式不合法（要求 ^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$）: " + id);
        }
        String[] parts = id.split("\\.");
        String namespace = parts[0];
        if (!SELF_DESCRIPTIVE_NAMESPACES.contains(namespace)
                && !containsVerbRoot(id)) {
            throw new IllegalStateException(
                    "ID 自描述度不足（namespace 不在白名单且不含动词词根）: " + id);
        }
    }

    private boolean containsVerbRoot(String text) {
        String lower = text.toLowerCase();
        return VERB_ROOTS.stream().anyMatch(lower::contains);
    }

    private void validateName(ToolContract tool) {
        String name = tool.name();
        if (name == null || name.isBlank() || !CHINESE_PATTERN.matcher(name).find()) {
            throw new IllegalStateException("name 必须是中文: " + tool.id());
        }
    }

    private void validateDescription(ToolContract tool) {
        String desc = tool.description();
        if (desc == null || desc.length() < MIN_DESCRIPTION_LENGTH) {
            throw new IllegalStateException(
                    "description 长度不足（要求 ≥ %d）: %s".formatted(MIN_DESCRIPTION_LENGTH, tool.id()));
        }
        // description 允许中英混排（中文化后由 trigram 索引召回；保留对动词词根的软提示）
        if (!CHINESE_PATTERN.matcher(desc).find() && !containsVerbRoot(desc.toLowerCase())) {
            log.warn("description 未检测到中文或英文动词，建议补充: toolId={}", tool.id());
        }
    }

    private void validateTags(ToolContract tool) {
        var tags = tool.tags();
        if (tags == null || tags.size() < MIN_TAG_COUNT) {
            throw new IllegalStateException(
                    "tags 数量不足（要求 ≥ %d）: %s".formatted(MIN_TAG_COUNT, tool.id()));
        }
        // tags 允许中英混排，仅校验非空 + 不重复
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalStateException("tags 含空白项: " + tool.id());
            }
        }
        Set<String> unique = new HashSet<>(tags);
        if (unique.size() != tags.size()) {
            throw new IllegalStateException("tags 有重复: " + tool.id());
        }
    }

    private void validateActions(ToolContract tool) {
        // v1 no-op：ActionMetadata 当前不承载 description 字段。
        // 未来若扩展 action metadata 需要对应规则时在此实现。
    }
}
