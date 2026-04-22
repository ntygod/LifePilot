package com.lifepilot.document.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

/**
 * document.create action 路由执行器。
 *
 * <p>统一承接 docx / xlsx / pptx 三类办公产物生成工具，
 * 对齐项目 {@link com.lifepilot.meta.infra.git.GitMutateActionDispatchExecutor} 模式，
 * 让 LLM 只看到一个 {@code document.create} 工具 + action 枚举，减少 schema 噪声。</p>
 *
 * <p>底层 3 个 executor 不做改动：本类仅作路由装配，action 参数决定转发目标。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateActionDispatchExecutor extends ActionDispatchExecutor {

    public DocumentCreateActionDispatchExecutor(DocumentCreateDocxToolExecutor docxExecutor,
                                                DocumentCreateXlsxToolExecutor xlsxExecutor,
                                                DocumentCreatePptxToolExecutor pptxExecutor) {
        // 三类产物均为写文件行为，风险一致（MEDIUM）、必须串行（SEQUENTIAL，避免同名落盘竞态），
        // 且落盘范围由底层 executor 自行限定在 storageDir，不依赖入参路径 —— pathTrees() 留空参即可
        register("docx",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ),
                docxExecutor::execute);
        register("xlsx",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ),
                xlsxExecutor::execute);
        register("pptx",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ),
                pptxExecutor::execute);
    }
}
