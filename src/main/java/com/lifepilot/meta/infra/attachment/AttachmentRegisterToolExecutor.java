package com.lifepilot.meta.infra.attachment;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 附件登记工具 —— 把 workspace 内的文件挂载为对话附件。
 *
 * <p>解决 LLM 生成图片/二进制产物（如 matplotlib 画的 png、document.create_docx
 * 输出的 docx）后无法在 chat UI 渲染的问题。底层用 {@link AttachmentRepository}
 * 的 orphan 模式（entry_id=null 入库），AgentPersistenceHandler.persistToolMediaAttachments
 * 在 assistant message 落地时通过 {@code backfillOrphanEntryIds} 自动关联，
 * 前端拿到 attachmentId 渲染图片或文件下载。</p>
 *
 * <p>典型用法：
 * <pre>
 * 1. shell.exec / code.execute 生成 ~/.zhiwei/workspace/chart.png
 * 2. file.attach(path=".../chart.png") → attachmentId=att_xxx
 * 3. 最终回答里引用 att_xxx，UI 渲染图片
 * </pre>
 *
 * <p>参数：
 * <ul>
 *   <li>{@code path}（必填）：workspace 内的文件绝对路径</li>
 *   <li>{@code displayName}（可选）：覆盖文件名（默认用 path 的 basename）</li>
 * </ul>
 *
 * <p>安全：硬约束 path 必须在 workspace 目录内（防 LLM 挂载系统敏感文件）。</p>
 *
 * @author zsg
 * @since 2026-04-28
 */
public final class AttachmentRegisterToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRegisterToolExecutor.class);
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;  // 50MB 上限

    private final AttachmentRepository attachmentRepository;
    private final WorkspaceResolver workspaceResolver;

    public AttachmentRegisterToolExecutor(AttachmentRepository attachmentRepository,
                                          WorkspaceResolver workspaceResolver) {
        this.attachmentRepository = attachmentRepository;
        this.workspaceResolver = workspaceResolver;
    }

    public ToolResult execute(ToolInput input) {
        Optional<String> sessionIdOpt = input.getContextValue(ToolContextKeys.SESSION_ID, String.class);
        if (sessionIdOpt.isEmpty()) {
            return ToolResult.error("缺少会话上下文，无法登记附件（context.sessionId 缺失）");
        }
        String sessionId = sessionIdOpt.get();

        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必填参数 path");
        }
        Path filePath = Path.of(pathStr).toAbsolutePath().normalize();

        // 硬约束：路径必须在 workspace 目录内
        Path workspaceRoot = workspaceResolver.resolve().toAbsolutePath().normalize();
        if (!filePath.startsWith(workspaceRoot)) {
            return ToolResult.error("仅允许登记 workspace 目录（" + workspaceRoot
                    + "）内的文件，拒绝: " + filePath);
        }

        if (!Files.exists(filePath)) {
            return ToolResult.error("文件不存在: " + filePath);
        }
        if (!Files.isRegularFile(filePath)) {
            return ToolResult.error("路径不是普通文件: " + filePath);
        }

        long fileSize;
        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            return ToolResult.error("读取文件大小失败: " + e.getMessage());
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            return ToolResult.error("文件过大不允许登记: size=" + fileSize
                    + " bytes, 上限 50MB");
        }
        if (fileSize == 0) {
            return ToolResult.error("空文件不允许登记: " + filePath);
        }

        String mimeType = detectMimeType(filePath);
        String displayName = input.getOptionalParam("displayName", String.class)
                .filter(s -> !s.isBlank())
                .orElse(filePath.getFileName().toString());

        try {
            // entryId=null orphan 模式 —— 后续 AgentPersistenceHandler.backfillOrphanEntryIds
            // 在 assistant message 落地时自动关联到当前 turn
            String attachmentId = attachmentRepository.saveForEntry(
                    null,
                    sessionId,
                    displayName,
                    filePath.toString(),
                    fileSize,
                    mimeType,
                    null  // url 留空，前端按 attachmentId 走文件下载/预览端点
            );

            var data = new LinkedHashMap<String, Object>();
            data.put("attachmentId", attachmentId);
            data.put("fileName", displayName);
            data.put("size", fileSize);
            data.put("mimeType", mimeType);
            data.put("path", filePath.toString());
            log.info("登记附件成功: attachmentId={}, sessionId={}, fileName={}, size={}, mimeType={}",
                    attachmentId, sessionId, displayName, fileSize, mimeType);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.warn("登记附件失败: path={}, error={}", filePath, e.getMessage());
            return ToolResult.error("登记附件失败: " + e.getMessage());
        }
    }

    private String detectMimeType(Path filePath) {
        try {
            String detected = Files.probeContentType(filePath);
            if (detected != null) return detected;
        } catch (IOException ignored) {
            // 回退到扩展名探测
        }
        String fallback = URLConnection.guessContentTypeFromName(filePath.getFileName().toString());
        return fallback != null ? fallback : "application/octet-stream";
    }
}
