package com.lifepilot.meta.infra.file.history;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件编辑历史工具提供者 — 构建 file.undo / file.redo / file.diff 三个工具。
 *
 * @author zsg
 * @since 2026-03-31
 */
public class FileEditToolProvider {

    private static final Logger log = LoggerFactory.getLogger(FileEditToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final FileEditHistory editHistory;

    public FileEditToolProvider(FileEditHistory editHistory) {
        this.editHistory = editHistory;
    }

    /**
     * 构建文件编辑历史工具列表。
     *
     * @return 包含 file.undo、file.redo、file.diff 的工具列表
     */
    public List<BuiltinTool> buildEditHistoryTools() {
        return List.of(
                buildUndoTool(),
                buildRedoTool(),
                buildDiffTool()
        );
    }

    /** 构建 file.undo 工具。 */
    private BuiltinTool buildUndoTool() {
        return BuiltinTool.builder()
                .id("file.undo")
                .category(ToolCategory.ACTION)
                .name("撤销文件编辑")
                .description("撤销对指定文件的最近一次修改，恢复到上一个版本。支持多次撤销直到会话起始状态")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "要撤销修改的文件路径")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeUndo)
                .build();
    }

    /** 构建 file.redo 工具。 */
    private BuiltinTool buildRedoTool() {
        return BuiltinTool.builder()
                .id("file.redo")
                .category(ToolCategory.ACTION)
                .name("重做文件编辑")
                .description("重做对指定文件的最近一次撤销操作，恢复到撤销前的版本")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "要重做修改的文件路径")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeRedo)
                .build();
    }

    /** 构建 file.diff 工具。 */
    private BuiltinTool buildDiffTool() {
        return BuiltinTool.builder()
                .id("file.diff")
                .category(ToolCategory.PERCEPTION)
                .name("文件差异对比")
                .description("查看文件自本次会话以来的修改差异（unified diff 格式）。不指定 path 时显示所有被修改文件的差异")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "要查看差异的文件路径（可选，不指定时显示所有文件差异）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeDiff)
                .build();
    }

    /** 执行 file.undo。 */
    private ToolResult executeUndo(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数 path: " + e.getMessage());
        }

        Path filePath = Path.of(pathStr);
        try {
            var snapshotOpt = editHistory.undo(filePath);
            if (snapshotOpt.isEmpty()) {
                return ToolResult.error("没有可撤销的编辑记录: " + pathStr);
            }

            var snapshot = snapshotOpt.get();
            var data = new LinkedHashMap<String, Object>();
            data.put("path", snapshot.path().toString());
            data.put("restoredBytes", snapshot.content().length);
            data.put("undoDepth", editHistory.undoDepth(filePath));
            data.put("redoDepth", editHistory.redoDepth(filePath));

            return ToolResult.success(Map.copyOf(data));
        } catch (IOException e) {
            log.error("文件撤销失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件撤销失败: " + e.getMessage());
        }
    }

    /** 执行 file.redo。 */
    private ToolResult executeRedo(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数 path: " + e.getMessage());
        }

        Path filePath = Path.of(pathStr);
        try {
            var snapshotOpt = editHistory.redo(filePath);
            if (snapshotOpt.isEmpty()) {
                return ToolResult.error("没有可重做的编辑记录: " + pathStr);
            }

            var snapshot = snapshotOpt.get();
            var data = new LinkedHashMap<String, Object>();
            data.put("path", snapshot.path().toString());
            data.put("restoredBytes", snapshot.content().length);
            data.put("undoDepth", editHistory.undoDepth(filePath));
            data.put("redoDepth", editHistory.redoDepth(filePath));

            return ToolResult.success(Map.copyOf(data));
        } catch (IOException e) {
            log.error("文件重做失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件重做失败: " + e.getMessage());
        }
    }

    /** 执行 file.diff。 */
    private ToolResult executeDiff(ToolInput input) {
        var pathOpt = input.getOptionalParam("path", String.class);

        try {
            String diffOutput;
            if (pathOpt.isPresent()) {
                diffOutput = editHistory.diff(Path.of(pathOpt.get()));
            } else {
                diffOutput = editHistory.diffAll();
            }

            if (diffOutput.isEmpty()) {
                return ToolResult.success(Map.of(
                        "diff", "",
                        "message", "没有检测到文件变更"
                ));
            }

            return ToolResult.success(Map.of("diff", diffOutput));
        } catch (IOException e) {
            log.error("计算文件差异失败: error={}", e.getMessage(), e);
            return ToolResult.error("计算文件差异失败: " + e.getMessage());
        }
    }
}
