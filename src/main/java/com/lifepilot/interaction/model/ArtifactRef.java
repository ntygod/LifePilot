package com.lifepilot.interaction.model;

import com.lifepilot.tool.model.ArtifactKind;

/**
 * 会话产物在响应链路上的轻量引用。
 *
 * <p>{@link com.lifepilot.tool.model.ToolArtifact} 是工具执行器层的产物结构，
 * 写入 {@code session_artifacts} 表后，上层用 {@link ArtifactRef} 在
 * {@link GatewayResponse}、SSE 事件、connector RPC 上传递引用 ——
 * 避免在响应流上塞 base64 字节，保持低延迟。</p>
 *
 * <p>渠道分发器在 {@code ChannelDeliveryDispatcher.buildArtifactDelivery(...)}
 * 内按 ref 拉 SessionArtifactRow 取 path、读字节、构造 connector 投递包。</p>
 *
 * @param artifactId {@code session_artifacts.id}，必填
 * @param fileName   文件名，必填
 * @param mimeType   RFC 6838 mimeType；为空时回落 {@code application/octet-stream}
 * @param kind       产物类型；为空时回落 {@link ArtifactKind#FILE}
 * @param size       字节数（构造时不再校验，由调用方保证 ≥ 0）
 *
 * @author zsg
 * @since 2026-05-17
 */
public record ArtifactRef(
        String artifactId,
        String fileName,
        String mimeType,
        ArtifactKind kind,
        long size
) {

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /**
     * 紧凑构造器：必填字段校验 + mimeType / kind 缺省值。
     */
    public ArtifactRef {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = DEFAULT_MIME_TYPE;
        }
        if (kind == null) {
            kind = ArtifactKind.FILE;
        }
    }
}
