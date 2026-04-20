package com.lifepilot.llm.multimodal;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

/**
 * 多模态请求模型。
 *
 * <p>{@code toolCallbacks} 承载 Agent 可调用的工具列表。当请求没有真正的多模态媒体
 * （image / audio / video）时，{@link MultimodalRouter} 会降级到纯文本路径，
 * 届时将 toolCallbacks 透传给 ChatModel，保证工具调用能力不丢失。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public record MultimodalRequest(
    String scene,
    String text,
    List<MediaContent> mediaList,
    @Nullable String outputSchema,
    @Nullable String preferredProviderId,
    @Nullable String modelName,
    @Nullable List<ToolCallback> toolCallbacks
) {
    public MultimodalRequest(String scene,
                             String text,
                             List<MediaContent> mediaList,
                             @Nullable String outputSchema) {
        this(scene, text, mediaList, outputSchema, null, null, null);
    }

    public MultimodalRequest(String scene,
                             String text,
                             List<MediaContent> mediaList,
                             @Nullable String outputSchema,
                             @Nullable String preferredProviderId) {
        this(scene, text, mediaList, outputSchema, preferredProviderId, null, null);
    }

    public MultimodalRequest(String scene,
                             String text,
                             List<MediaContent> mediaList,
                             @Nullable String outputSchema,
                             @Nullable String preferredProviderId,
                             @Nullable String modelName) {
        this(scene, text, mediaList, outputSchema, preferredProviderId, modelName, null);
    }

    public MultimodalRequest {
        Objects.requireNonNull(scene, "场景不能为空");
        Objects.requireNonNull(text, "文本提示词不能为空");
        mediaList = mediaList != null ? List.copyOf(mediaList) : List.of();
        toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : null;
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

    /**
     * 判断请求中是否包含音频附件。
     *
     * @return 包含音频时返回 true
     */
    public boolean hasAudio() {
        return mediaList.stream().anyMatch(mc -> mc.mimeType().startsWith("audio/"));
    }

    /**
     * 判断请求是否携带工具列表。
     *
     * @return 有非空工具列表时返回 true
     */
    public boolean hasToolCallbacks() {
        return toolCallbacks != null && !toolCallbacks.isEmpty();
    }
}
