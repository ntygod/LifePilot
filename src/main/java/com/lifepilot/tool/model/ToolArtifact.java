package com.lifepilot.tool.model;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.lang.Nullable;

/**
 * 工具执行后产生的文件产物。
 *
 * <p>对齐 LangChain {@code ToolMessage.artifact} 概念：作为工具执行结果中
 * 「不喂回 LLM、但需要被下游程序感知」的产物载体，让 Agent 主循环把它升级为
 * 会话级 {@code session_artifacts} 一等公民、并通过渠道分发到飞书 / 企微 /
 * 钉钉 / Telegram / Web / Tauri 桌面端。</p>
 *
 * <p>路径必须是绝对路径；调用方负责确保路径在 workspace 白名单内
 * （由 {@code ArtifactFilter.isInWorkspaceRoot(...)} 校验，越界产物会被
 * 工具执行器层直接丢弃）。</p>
 *
 * @param path     绝对路径
 * @param fileName 不含路径的文件名（含扩展名）
 * @param mimeType RFC 6838 mimeType；缺省为 {@code application/octet-stream}
 * @param size     字节数；构造时校验非负
 * @param kind     产物类型；缺省按 mimeType 自动推断
 * @param summary  可选简短描述
 *
 * @author zsg
 * @since 2026-05-17
 */
public record ToolArtifact(
        String path,
        String fileName,
        String mimeType,
        long size,
        ArtifactKind kind,
        @Nullable String summary
) {

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /**
     * 紧凑构造器：必填字段校验、mimeType / kind 缺省值。
     */
    public ToolArtifact {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size 不能为负: " + size);
        }
        mimeType = (mimeType != null && !mimeType.isBlank()) ? mimeType : DEFAULT_MIME_TYPE;
        kind = (kind != null) ? kind : ArtifactKind.fromMimeType(mimeType);
    }

    /**
     * 从文件路径构造 ToolArtifact，自动读取 size、推断 mimeType / kind。
     *
     * @param filePath 待登记的文件路径；调用方应已确保文件存在
     * @return 构造完成的 ToolArtifact
     * @throws IOException 文件不存在或无法读取大小时
     */
    public static ToolArtifact fromFile(Path filePath) throws IOException {
        return fromFile(filePath, null);
    }

    /**
     * 从文件路径构造 ToolArtifact，并附加 summary。
     *
     * @param filePath 待登记的文件路径
     * @param summary  可选简短描述
     * @return 构造完成的 ToolArtifact
     * @throws IOException 文件不存在或无法读取大小时
     */
    public static ToolArtifact fromFile(Path filePath, @Nullable String summary) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("filePath 不能为 null");
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        String fileName = normalized.getFileName().toString();
        String mime = URLConnection.guessContentTypeFromName(fileName);
        if (mime == null || mime.isBlank()) {
            mime = DEFAULT_MIME_TYPE;
        }
        ArtifactKind kind = ArtifactKind.fromMimeType(mime);
        return new ToolArtifact(normalized.toString(), fileName, mime, size, kind, summary);
    }
}
