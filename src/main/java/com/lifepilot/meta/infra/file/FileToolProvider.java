package com.lifepilot.meta.infra.file;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.config.GatewayDeliveryProperties;
import com.lifepilot.knowledge.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import com.lifepilot.config.path.PathAccessControl;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 文件工具提供者 — 3 个工具：file.read / file.write / file.manage。
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileToolProvider {

    private final MetaProperties properties;
    @Nullable
    private final FileEditHistory editHistory;
    @Nullable
    private final LintHookExecutor lintHook;
    @Nullable
    private final AttachmentRepository attachmentRepository;
    @Nullable
    private final PathAccessControl pathAccessControl;
    private final DocumentParserService documentParserService;
    @Nullable
    private final Path workspaceRoot;
    private final ArtifactFilterConfig artifactFilterConfig;

    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook,
                            @Nullable AttachmentRepository attachmentRepository,
                            @Nullable PathAccessControl pathAccessControl) {
        this(properties, editHistory, lintHook, attachmentRepository, pathAccessControl, null, null);
    }

    /**
     * 完整构造函数 — 注入 WorkspaceResolver 与 GatewayDeliveryProperties，让 file.write
     * 在写入成功后登记 ToolArtifact 供渠道分发。历史调用方继续使用 5 参构造器（artifact
     * 登记会被关闭）。
     */
    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook,
                            @Nullable AttachmentRepository attachmentRepository,
                            @Nullable PathAccessControl pathAccessControl,
                            @Nullable WorkspaceResolver workspaceResolver,
                            @Nullable GatewayDeliveryProperties deliveryProperties) {
        this.properties = properties;
        this.editHistory = editHistory;
        this.lintHook = lintHook;
        this.attachmentRepository = attachmentRepository;
        this.pathAccessControl = pathAccessControl;
        this.documentParserService = DocumentParserService.buildDefault();
        this.workspaceRoot = workspaceResolver != null ? workspaceResolver.resolve() : null;
        this.artifactFilterConfig = deliveryProperties != null
                ? deliveryProperties.toFilterConfig()
                : ArtifactFilterConfig.defaultConfig();
    }

    public List<BuiltinTool> buildFileTools() {
        var fileAccess = properties.getInfra().getFile();
        var securityChecker = new PathSecurityChecker(fileAccess);
        var fileEditConfig = properties.getInfra().getFileEdit();
        var tools = new ArrayList<BuiltinTool>();

        tools.add(buildFileReadTool(securityChecker, properties.getInfra().getFile()));
        tools.add(buildFileWriteTool(securityChecker, fileEditConfig));
        tools.add(buildFileManageTool(securityChecker));
        return List.copyOf(tools);
    }

    // ──────── file.read: read + list + search + info + attach ────────

    private BuiltinTool buildFileReadTool(PathSecurityChecker securityChecker,
                                           MetaProperties.Infra.FileAccess fileAccess) {
        var actions = new LinkedHashSet<>(List.of("read", "list", "search", "info"));
        if (attachmentRepository != null) actions.add("attach");

        var props = new LinkedHashMap<String, Object>();
        props.put("action", Map.of(
                "type", "string",
                "enum", List.copyOf(actions),
                "description", "read=读取文件 list=列目录 search=递归搜内容 info=查看元数据" +
                        (attachmentRepository != null ? " attach=处理附件" : "")));
        props.put("path", Map.of("type", "string",
                "description", "文件或目录路径。read/list/search/info 必填"));
        props.put("attachmentId", Map.of("type", "string",
                "description", "action=attach 时的附件 ID"));
        props.put("encoding", Map.of("type", "string",
                "description", "action=read 时编码，默认 UTF-8"));
        props.put("startLine", Map.of("type", "integer",
                "description", "action=read 时起始行号（1-based）"));
        props.put("endLine", Map.of("type", "integer",
                "description", "action=read 时结束行号（1-based）"));
        props.put("maxChars", Map.of("type", "integer",
                "description", "action=read 时最大字符数，默认 30000"));
        props.put("pattern", Map.of("type", "string",
                "description", "action=list 时 glob 过滤 / search 时正则"));
        props.put("maxDepth", Map.of("type", "integer",
                "description", "action=list/search 时最大深度"));
        props.put("maxEntries", Map.of("type", "integer",
                "description", "action=list 时最大条目，默认 200"));
        props.put("maxResults", Map.of("type", "integer",
                "description", "action=search 时最大结果，默认 50"));
        props.put("contextLines", Map.of("type", "integer",
                "description", "action=search 时上下文行数，默认 0"));

        return BuiltinTool.builder()
                .id("file.read")
                .category(ToolCategory.PERCEPTION)
                .name("文件操作")
                .description("""
                        读文件 + 目录浏览 + 附件解析。action 默认 read。
                        read — 读取文件（docx/xlsx/pptx/pdf/md/csv 自动解析），仅可访问 skills 与 workspace 目录。
                        list — 列出目录内容。search — 递归搜索文件内容。info — 查看元数据。""" +
                        (attachmentRepository != null ? " attach — 处理对话附件。" : ""))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", props,
                        "dependentRequired", Map.of(
                                "read", List.of("path"),
                                "list", List.of("path"),
                                "search", List.of("path", "pattern"),
                                "info", List.of("path")
                        ))))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE, ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")))
                .tags(List.of("读取", "文件", "列表", "目录", "搜索", "附件", "read", "file", "list", "search"))
                .executor(input -> {
                    String action = input.getOptionalParam("action", String.class).orElse("read");
                    return switch (action) {
                        case "read" -> new FileReadToolExecutor(securityChecker,
                                fileAccess.getDefaultMaxChars(), pathAccessControl,
                                attachmentRepository, documentParserService).execute(input);
                        case "list" -> new FileListToolExecutor(securityChecker,
                                fileAccess.getDefaultMaxEntries()).execute(input);
                        case "search" -> new FileSearchToolExecutor(securityChecker).execute(input);
                        case "info" -> new FileInfoToolExecutor(securityChecker).execute(input);
                        case "attach" -> new FileReadToolExecutor(securityChecker,
                                fileAccess.getDefaultMaxChars(), pathAccessControl,
                                attachmentRepository, documentParserService).execute(input);
                        default -> ToolResult.error("不支持的 action: " + action);
                    };
                })
                .build();
    }

    // ──────── file.write: write + edit + history ────────

    private BuiltinTool buildFileWriteTool(PathSecurityChecker securityChecker,
                                            MetaProperties.Infra.FileEdit fileEditConfig) {
        var actions = new LinkedHashSet<>(List.of("write", "insert", "replace", "delete_line", "find_replace"));
        boolean hasHistory = editHistory != null;
        if (hasHistory) {
            actions.addAll(List.of("undo", "redo", "diff"));
        }

        var props = new LinkedHashMap<String, Object>();
        props.put("action", Map.of(
                "type", "string",
                "enum", List.copyOf(actions),
                "description", "write=创建/覆盖/追加 insert/replace/delete_line=行级编辑 find_replace=文本替换" +
                        (hasHistory ? " undo=撤销 redo=重做 diff=查看差异" : "")));
        props.put("path", Map.of("type", "string",
                "description", "目标文件路径。所有 action 必填"));
        props.put("content", Map.of("type", "string",
                "description", "action=write 时文件内容; insert/replace 时操作内容"));
        props.put("mode", Map.of("type", "string",
                "description", "action=write 时：write（覆写，默认）或 append（追加）"));
        props.put("line", Map.of("type", "integer",
                "description", "action=insert/replace/delete_line 时目标行号（1-based）"));
        props.put("endLine", Map.of("type", "integer",
                "description", "action=replace/delete_line 时结束行号"));
        props.put("oldText", Map.of("type", "string",
                "description", "action=find_replace 时查找文本"));
        props.put("newText", Map.of("type", "string",
                "description", "action=find_replace 时替换文本"));

        return BuiltinTool.builder()
                .id("file.write")
                .category(ToolCategory.ACTION)
                .name("文件编辑")
                .description("""
                        写文件 + 内容编辑 + 编辑历史。action 默认 write。
                        write — 创建/覆盖/追加文件（父目录自动创建）。
                        insert — 行级插入。replace — 行级替换。delete_line — 行级删除。find_replace — 搜索替换。""" +
                        (hasHistory ? " undo — 撤销最近编辑。redo — 重做。diff — 查看 unified diff。" : ""))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", props,
                        "dependentRequired", Map.of(
                                "write", List.of("path", "content"),
                                "insert", List.of("path", "line", "content"),
                                "replace", List.of("path", "line"),
                                "delete_line", List.of("path", "line"),
                                "find_replace", List.of("path", "oldText", "newText"),
                                "undo", List.of("path"),
                                "redo", List.of("path"),
                                "diff", List.of("path")
                        ))))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE, ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")))
                .tags(List.of("写入", "编辑", "修改", "文件", "替换", "撤销", "重做", "write", "edit", "file"))
                .executor(input -> {
                    String action = input.getOptionalParam("action", String.class).orElse("write");
                    return switch (action) {
                        case "write" -> new FileWriteToolExecutor(securityChecker,
                                editHistory, lintHook, fileEditConfig,
                                workspaceRoot, artifactFilterConfig).execute(input);
                        case "insert", "replace", "delete_line", "find_replace" ->
                                new FilePatchToolExecutor(securityChecker,
                                        editHistory, lintHook, fileEditConfig).execute(input);
                        case "undo" -> hasHistory
                                ? executeUndo(input, editHistory)
                                : ToolResult.error("编辑历史未启用");
                        case "redo" -> hasHistory
                                ? executeRedo(input, editHistory)
                                : ToolResult.error("编辑历史未启用");
                        case "diff" -> hasHistory
                                ? executeDiff(input, editHistory)
                                : ToolResult.error("编辑历史未启用");
                        default -> ToolResult.error("不支持的 action: " + action);
                    };
                })
                .build();
    }

    private static ToolResult executeUndo(ToolInput input, FileEditHistory history) {
        String path = input.getOptionalParam("path", String.class).orElse("");
        if (path.isBlank()) return ToolResult.error("undo 需要 path 参数");
        try {
            var snapshot = history.undo(java.nio.file.Path.of(path));
            return ToolResult.success(Map.of("path", path,
                    "restored", snapshot.map(s -> "已恢复到快照").orElse("无快照可撤销")));
        } catch (Exception e) {
            return ToolResult.error("撤销失败: " + e.getMessage());
        }
    }

    private static ToolResult executeRedo(ToolInput input, FileEditHistory history) {
        String path = input.getOptionalParam("path", String.class).orElse("");
        if (path.isBlank()) return ToolResult.error("redo 需要 path 参数");
        try {
            var snapshot = history.redo(java.nio.file.Path.of(path));
            return ToolResult.success(Map.of("path", path,
                    "restored", snapshot.map(s -> "已重做").orElse("无快照可重做")));
        } catch (Exception e) {
            return ToolResult.error("重做失败: " + e.getMessage());
        }
    }

    private static ToolResult executeDiff(ToolInput input, FileEditHistory history) {
        String path = input.getOptionalParam("path", String.class).orElse("");
        if (path.isBlank()) return ToolResult.error("diff 需要 path 参数");
        try {
            String diff = history.diff(java.nio.file.Path.of(path));
            return ToolResult.success(Map.of("path", path, "diff", diff != null ? diff : ""));
        } catch (Exception e) {
            return ToolResult.error("获取 diff 失败: " + e.getMessage());
        }
    }

    // ──────── file.manage: move + copy + delete + mkdir + rename ────────

    private BuiltinTool buildFileManageTool(PathSecurityChecker securityChecker) {
        var actions = List.of("move", "copy", "delete", "mkdir", "rename");

        var props = new LinkedHashMap<String, Object>();
        props.put("action", Map.of(
                "type", "string",
                "enum", actions,
                "description", "move=移动 copy=复制 delete=删除 mkdir=建目录 rename=重命名"));
        props.put("source", Map.of("type", "string",
                "description", "action=move/copy/rename 时源路径"));
        props.put("destination", Map.of("type", "string",
                "description", "action=move/copy/rename 时目标路径"));
        props.put("path", Map.of("type", "string",
                "description", "action=delete/mkdir 时目标路径"));
        props.put("overwrite", Map.of("type", "boolean",
                "description", "action=move/copy 时覆盖已有"));
        props.put("recursive", Map.of("type", "boolean",
                "description", "action=delete 时递归删除; copy 时递归复制"));

        return BuiltinTool.builder()
                .id("file.manage")
                .category(ToolCategory.ACTION)
                .name("文件管理")
                .description("文件系统操作：move/copy/delete/mkdir/rename。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", props,
                        "dependentRequired", Map.of(
                                "move", List.of("source", "destination"),
                                "copy", List.of("source", "destination"),
                                "delete", List.of("path"),
                                "mkdir", List.of("path"),
                                "rename", List.of("source", "destination")
                        ))))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE, ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination", "path")))
                .tags(List.of("管理", "移动", "复制", "删除", "重命名", "目录", "manage", "move", "copy", "delete"))
                .executor(input -> {
                    String action = input.getOptionalParam("action", String.class).orElse("move");
                    return switch (action) {
                        case "move" -> new FileMoveToolExecutor(securityChecker).execute(input);
                        case "copy" -> new FileCopyToolExecutor(securityChecker).execute(input);
                        case "delete" -> new FileDeleteToolExecutor(securityChecker).execute(input);
                        case "mkdir" -> new FileMkdirToolExecutor(securityChecker).execute(input);
                        case "rename" -> renameFile(input, securityChecker);
                        default -> ToolResult.error("不支持的 action: " + action);
                    };
                })
                .build();
    }

    private static ToolResult renameFile(ToolInput input, PathSecurityChecker securityChecker) {
        String source = input.getOptionalParam("source", String.class).orElse("");
        String destination = input.getOptionalParam("destination", String.class).orElse("");
        if (source.isBlank() || destination.isBlank())
            return ToolResult.error("rename 需要 source 和 destination 参数");
        try {
            var srcPath = java.nio.file.Path.of(source);
            var dstPath = java.nio.file.Path.of(destination);
            securityChecker.check(srcPath).ifPresent(err -> { throw new SecurityException(err); });
            securityChecker.check(dstPath).ifPresent(err -> { throw new SecurityException(err); });
            java.nio.file.Files.move(srcPath, dstPath);
            return ToolResult.success(Map.of("source", source, "destination", destination));
        } catch (Exception e) {
            return ToolResult.error("重命名失败: " + e.getMessage());
        }
    }
}
