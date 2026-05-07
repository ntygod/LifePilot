package com.lifepilot.meta.infra.attachment;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件附件挂载工具提供者 —— 把 workspace 内的文件挂载为对话附件。
 *
 * <p>归到 {@code file.*} 命名空间（与 file.read/write/list/edit/manage 同组），
 * 而非另起 {@code attachment} namespace —— 跟项目偏好一致：避免单一动作起独立
 * namespace，复用语义同组的现有 namespace。</p>
 *
 * <p>替代 LLM 用 base64 字符串绕路返回图片的反模式：
 * 工具产物（matplotlib 画图、python-docx 生成文档等）落到 workspace 后，
 * LLM 调 {@code file.attach(path=...)} 拿到 attachmentId，最终回答里
 * reference 该 ID，前端拿到自动渲染图片/下载入口。</p>
 *
 * @author zsg
 * @since 2026-04-28
 */
public final class AttachmentToolProvider {

    private final AttachmentRepository attachmentRepository;
    private final WorkspaceResolver workspaceResolver;

    public AttachmentToolProvider(AttachmentRepository attachmentRepository,
                                  WorkspaceResolver workspaceResolver) {
        this.attachmentRepository = attachmentRepository;
        this.workspaceResolver = workspaceResolver;
    }

    public List<BuiltinTool> buildAttachmentTools() {
        return List.of(buildRegisterTool(
                new AttachmentRegisterToolExecutor(attachmentRepository, workspaceResolver)));
    }

    private BuiltinTool buildRegisterTool(AttachmentRegisterToolExecutor executor) {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of("type", "string",
                "description", "要登记的文件绝对路径，必须在 workspace 目录内（如 ~/.zhiwei/workspace/chart.png）"));
        props.put("displayName", Map.of("type", "string",
                "description", "附件显示名（可选，默认用文件名）"));

        return BuiltinTool.builder()
                .id("file.attach")
                .category(ToolCategory.ACTION)
                .name("挂载对话附件")
                .description(
                        "把 workspace 内的文件挂载为对话附件，返回 attachmentId 在最终回答里 reference，"
                                + "前端拿到 ID 自动渲染图片/文件下载入口。"
                                + "用于工具生成的 png / pdf / docx 等二进制产物展示给用户 —— "
                                + "**禁止** 用 base64 字符串作为文本返回模拟附件。"
                                + "约束：仅允许 workspace 目录内文件，单文件 ≤50MB。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", props
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(List.of("附件", "挂载", "图片", "下载", "展示", "file", "attach", "图表", "产物"))
                .executor(executor::execute)
                .build();
    }
}
