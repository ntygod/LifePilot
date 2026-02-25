package com.lifepilot.media.video;

import java.util.List;

import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.lang.Nullable;

/**
 * 视频处理结果。
 *
 * @param keyFrames       关键帧列表（已预处理的 MediaContent）
 * @param transcript      音轨转录文本（可能为 null，如静音视频）
 * @param durationSeconds 视频时长（秒）
 * @param frameCount      提取的关键帧数量
 *
 * @author zsg
 * @since 2026-07-01
 */
public record VideoProcessResult(
    List<MediaContent> keyFrames,
    @Nullable String transcript,
    int durationSeconds,
    int frameCount
) {
    public VideoProcessResult {
        keyFrames = List.copyOf(keyFrames);
    }
}
