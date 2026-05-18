package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 历史消息中的产物引用信息 —— 对齐 SSE artifact-ref 事件 payload。
 *
 * <p>前端加载历史消息时，通过此 DTO 回填 artifactRefs，让 ArtifactCard 能渲染。</p>
 *
 * @param artifactId  session_artifacts.id
 * @param fileName    文件名
 * @param mimeType    MIME 类型
 * @param kind        IMAGE / FILE
 * @param size        字节大小
 * @param downloadUrl 下载 URL（后端拼接）
 *
 * @author zsg
 * @since 2026-05-18
 */
public record ArtifactRefInfo(
        String artifactId,
        String fileName,
        String mimeType,
        String kind,
        long size,
        @Nullable String downloadUrl
) {
}
