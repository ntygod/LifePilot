package com.lifepilot.llm.multimodal;

import java.util.List;
import java.util.Objects;

import org.springframework.lang.Nullable;

/**
 * 多模态请求模型。
 *
 * @author zsg
 * @since 2026-07-01
 */
public record MultimodalRequest(
    String scene,
    String text,
    List<MediaContent> mediaList,
    @Nullable String outputSchema
) {
    public MultimodalRequest {
        Objects.requireNonNull(scene, "场景不能为空");
        Objects.requireNonNull(text, "文本提示词不能为空");
        mediaList = mediaList != null ? List.copyOf(mediaList) : List.of();
    }

    /**
     * 判断请求中是否包含图片附件。
     *
     * @return 包含图片时返回 true
     */
    public boolean hasImages() {
        return mediaList.stream().anyMatch(mc -> mc.mimeType().startsWith("image/"));
    }

    /**
     * 判断请求中是否包含视频附件。
     *
     * @return 包含视频时返回 true
     */
    public boolean hasVideos() {
        return mediaList.stream().anyMatch(mc -> mc.mimeType().startsWith("video/"));
    }
}
