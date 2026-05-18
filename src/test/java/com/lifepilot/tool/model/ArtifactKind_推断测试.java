package com.lifepilot.tool.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArtifactKind#fromMimeType(String)} 推断行为测试。
 *
 * <p>覆盖 image/* 前缀映射、null / 空白回退、以及大小写归一化。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class ArtifactKind_推断测试 {

    @Test
    @DisplayName("image/png 等 image 类型应识别为 IMAGE")
    void image前缀返回IMAGE() {
        assertThat(ArtifactKind.fromMimeType("image/png")).isEqualTo(ArtifactKind.IMAGE);
        assertThat(ArtifactKind.fromMimeType("image/jpeg")).isEqualTo(ArtifactKind.IMAGE);
        assertThat(ArtifactKind.fromMimeType("image/svg+xml")).isEqualTo(ArtifactKind.IMAGE);
        assertThat(ArtifactKind.fromMimeType("image/webp")).isEqualTo(ArtifactKind.IMAGE);
    }

    @Test
    @DisplayName("非 image 前缀的 mimeType 应识别为 FILE")
    void 非image前缀返回FILE() {
        assertThat(ArtifactKind.fromMimeType("application/pdf")).isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("text/plain")).isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("video/mp4")).isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("audio/mpeg")).isEqualTo(ArtifactKind.FILE);
    }

    @Test
    @DisplayName("大小写不敏感")
    void 大小写不敏感() {
        assertThat(ArtifactKind.fromMimeType("IMAGE/PNG")).isEqualTo(ArtifactKind.IMAGE);
        assertThat(ArtifactKind.fromMimeType("Image/Jpeg")).isEqualTo(ArtifactKind.IMAGE);
    }

    @Test
    @DisplayName("null 或空白回退为 FILE")
    void null或空白回退为FILE() {
        assertThat(ArtifactKind.fromMimeType(null)).isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("")).isEqualTo(ArtifactKind.FILE);
        assertThat(ArtifactKind.fromMimeType("   ")).isEqualTo(ArtifactKind.FILE);
    }
}
