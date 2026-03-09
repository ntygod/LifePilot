package com.lifepilot.sync.connector.obsidian;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.connector.JsonHelper;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/**
 * Obsidian 连接器，使用 Java NIO 操作本地 Vault 文件系统。
 *
 * <p>直接读写 Markdown 文件（YAML frontmatter + body），不依赖 Obsidian 应用运行。
 * 使用文件修改时间戳进行增量变更检测，跳过无 type 字段或 type 不在
 * [todo, schedule, habit] 中的文件。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class ObsidianConnector implements SyncConnector {

    private static final Logger log = LoggerFactory.getLogger(ObsidianConnector.class);

    private static final String CONNECTOR_TYPE = "obsidian";
    private static final Set<String> SUPPORTED_TYPES = Set.of("todo", "schedule", "habit");

    private final SyncProperties properties;
    private final FieldMapping<com.lifepilot.skill.builtin.todo.TodoItem, String> todoMapping;
    private final FieldMapping<com.lifepilot.skill.builtin.schedule.ScheduleItem, String> scheduleMapping;
    private final FieldMapping<com.lifepilot.skill.builtin.habit.HabitItem, String> habitMapping;

    public ObsidianConnector(SyncProperties properties) {
        this.properties = properties;
        this.todoMapping = ObsidianFieldMapping.todoMapping();
        this.scheduleMapping = ObsidianFieldMapping.scheduleMapping();
        this.habitMapping = ObsidianFieldMapping.habitMapping();
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public ConnectionTestResult testConnection(SyncProfile profile) {
        long start = System.currentTimeMillis();
        try {
            var vaultPath = getVaultPath(profile);
            var path = Path.of(vaultPath);

            if (!Files.exists(path)) {
                long elapsed = System.currentTimeMillis() - start;
                return new ConnectionTestResult(false, elapsed,
                        "Vault 目录不存在: " + vaultPath);
            }
            if (!Files.isDirectory(path)) {
                long elapsed = System.currentTimeMillis() - start;
                return new ConnectionTestResult(false, elapsed,
                        "路径不是目录: " + vaultPath);
            }
            if (!Files.isReadable(path)) {
                long elapsed = System.currentTimeMillis() - start;
                return new ConnectionTestResult(false, elapsed,
                        "Vault 目录不可读: " + vaultPath);
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Obsidian Vault 连接测试成功: path={}, 耗时={}ms", vaultPath, elapsed);
            return new ConnectionTestResult(true, elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Obsidian 连接测试失败: {}", e.getMessage());
            return new ConnectionTestResult(false, elapsed, "连接失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken) {
        var vaultPath = getVaultPath(profile);
        var vaultDir = Path.of(vaultPath);

        if (!Files.exists(vaultDir) || !Files.isDirectory(vaultDir)) {
            throw new SyncException.ConnectionException("Obsidian Vault 目录不存在: " + vaultPath);
        }

        boolean isFullSync = (syncToken == null || syncToken.isBlank());
        Instant lastSyncTime = isFullSync ? Instant.EPOCH : parseInstant(syncToken);

        var created = new ArrayList<RemoteChangeSet.RemoteEntity>();
        var updated = new ArrayList<RemoteChangeSet.RemoteEntity>();

        try (var stream = Files.walk(vaultDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        try {
                            processMarkdownFile(filePath, lastSyncTime, isFullSync, created, updated);
                        } catch (Exception e) {
                            log.debug("Obsidian 文件处理跳过: file={}, 原因={}", filePath, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new SyncException.ConnectionException("Obsidian Vault 遍历失败: " + e.getMessage(), e);
        }

        // 新的 syncToken 使用当前时间戳
        var newSyncToken = Instant.now().toString();

        log.info("Obsidian 拉取完成: 新增={}, 更新={}", created.size(), updated.size());
        return new RemoteChangeSet(created, updated, List.of(), newSyncToken);
    }

    @Override
    public PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations) {
        var vaultPath = getVaultPath(profile);
        var vaultDir = Path.of(vaultPath);

        if (!Files.exists(vaultDir)) {
            try {
                Files.createDirectories(vaultDir);
            } catch (IOException e) {
                throw new SyncException.ConnectionException(
                        "Obsidian Vault 目录创建失败: " + vaultPath, e);
            }
        }

        int successCount = 0;
        var errors = new ArrayList<PushResult.PushError>();

        for (var op : operations) {
            try {
                switch (op) {
                    case SyncOperation.Create create -> {
                        var content = convertToMarkdown(create.localEntityType(), create.remotePayload());
                        var fileName = sanitizeFileName(create.localEntityId()) + ".md";
                        var filePath = vaultDir.resolve(fileName);
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                        successCount++;
                    }
                    case SyncOperation.Update update -> {
                        var content = convertToMarkdown(update.localEntityType(), update.remotePayload());
                        // 尝试找到已有文件，否则用 remoteEntityId 作为文件名
                        var fileName = sanitizeFileName(update.remoteEntityId()) + ".md";
                        var filePath = vaultDir.resolve(fileName);
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                        successCount++;
                    }
                    case SyncOperation.Delete delete -> {
                        var fileName = sanitizeFileName(delete.remoteEntityId()) + ".md";
                        var filePath = vaultDir.resolve(fileName);
                        if (Files.exists(filePath)) {
                            Files.delete(filePath);
                        }
                        successCount++;
                    }
                }
            } catch (Exception e) {
                var entityId = switch (op) {
                    case SyncOperation.Create c -> c.localEntityId();
                    case SyncOperation.Update u -> u.localEntityId();
                    case SyncOperation.Delete d -> d.localEntityId();
                };
                errors.add(new PushResult.PushError(entityId, "文件操作失败: " + e.getMessage()));
            }
        }

        log.info("Obsidian 推送完成: 成功={}, 失败={}", successCount, errors.size());
        return new PushResult(successCount, errors.size(), errors);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取 Vault 目录路径。
     */
    private String getVaultPath(SyncProfile profile) {
        // 优先从 connectionParamsJson 获取
        var json = profile.connectionParamsJson();
        if (json != null && !json.isBlank()) {
            var params = JsonHelper.parseSimpleJsonToMap(json);
            var path = params.get("vaultPath");
            if (path != null && !path.isBlank()) {
                return path;
            }
        }
        // 回退到全局配置
        return properties.getObsidianVaultPath();
    }

    /**
     * 处理单个 Markdown 文件，解析 frontmatter 并判断是否需要同步。
     */
    private void processMarkdownFile(Path filePath, Instant lastSyncTime, boolean isFullSync,
                                     List<RemoteChangeSet.RemoteEntity> created,
                                     List<RemoteChangeSet.RemoteEntity> updated) throws IOException {
        var content = Files.readString(filePath, StandardCharsets.UTF_8);
        var result = YamlFrontmatterParser.parse(content);
        var fm = result.frontmatter();

        // 检查 type 字段
        var typeObj = fm.get("type");
        if (typeObj == null) return;
        var type = typeObj.toString().toLowerCase();
        if (!SUPPORTED_TYPES.contains(type)) return;

        // 获取文件修改时间
        var attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        var fileModified = attrs.lastModifiedTime().toInstant();

        // 增量过滤：文件修改时间 > 上次同步时间
        if (!isFullSync && !fileModified.isAfter(lastSyncTime)) {
            return;
        }

        // 构建实体类型
        var entityType = switch (type) {
            case "todo" -> "TodoItem";
            case "schedule" -> "ScheduleItem";
            case "habit" -> "HabitItem";
            default -> null;
        };
        if (entityType == null) return;

        // 使用文件名（不含扩展名）作为远程 ID
        var fileName = filePath.getFileName().toString();
        var remoteId = fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3) : fileName;

        // 从 frontmatter 提取 zhiwei_id 作为备选
        var zhiweiId = fm.get("zhiwei_id");
        if (zhiweiId != null) {
            remoteId = zhiweiId.toString();
        }

        // 构建 fields Map
        var fields = new LinkedHashMap<String, Object>(fm);
        fields.put("_body", result.body());
        fields.put("_file_path", filePath.toString());
        fields.put("_raw_content", content);

        var entity = new RemoteChangeSet.RemoteEntity(
                remoteId, entityType, fields, null, fileModified.toString());

        if (isFullSync) {
            created.add(entity);
        } else {
            updated.add(entity);
        }
    }

    /**
     * 将本地实体转换为 Markdown 文本。
     */
    private String convertToMarkdown(String entityType, Object payload) {
        if (payload instanceof String s) return s;
        // payload 应该已经是 Markdown 文本
        return payload.toString();
    }

    /**
     * 清理文件名，移除不安全字符。
     */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // 移除文件系统不安全字符
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 安全解析 Instant，失败时返回 EPOCH。
     */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
