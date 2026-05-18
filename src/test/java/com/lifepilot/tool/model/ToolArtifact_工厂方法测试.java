package com.lifepilot.tool.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolArtifact} 工厂方法与紧凑构造器行为测试。
 *
 * <p>覆盖 mimeType / kind 自动推断、size 校验、null 路径异常、绝对路径归一化。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class ToolArtifact_工厂方法测试 {

    @Test
    @DisplayName("fromFile 自动推断 size + mimeType + kind")
    void fromFile自动推断字段(@TempDir Path tmp) throws IOException {
        Path png = tmp.resolve("chart.png");
        Files.write(png, new byte[]{1, 2, 3, 4, 5});

        ToolArtifact artifact = ToolArtifact.fromFile(png);

        assertThat(artifact.path()).isEqualTo(png.toAbsolutePath().normalize().toString());
        assertThat(artifact.fileName()).isEqualTo("chart.png");
        assertThat(artifact.mimeType()).isEqualTo("image/png");
        assertThat(artifact.size()).isEqualTo(5);
        assertThat(artifact.kind()).isEqualTo(ArtifactKind.IMAGE);
        assertThat(artifact.summary()).isNull();
    }

    @Test
    @DisplayName("fromFile 对未识别扩展名回落 application/octet-stream + FILE")
    void fromFile_未识别扩展名_回落octet_stream(@TempDir Path tmp) throws IOException {
        Path bin = tmp.resolve("data.xyz");
        Files.write(bin, new byte[]{0});

        ToolArtifact artifact = ToolArtifact.fromFile(bin);

        assertThat(artifact.mimeType()).isEqualTo("application/octet-stream");
        assertThat(artifact.kind()).isEqualTo(ArtifactKind.FILE);
    }

    @Test
    @DisplayName("fromFile 携带 summary")
    void fromFile_携带summary(@TempDir Path tmp) throws IOException {
        Path docx = tmp.resolve("report.docx");
        Files.write(docx, new byte[]{0});

        ToolArtifact artifact = ToolArtifact.fromFile(docx, "月度销售报告");

        assertThat(artifact.summary()).isEqualTo("月度销售报告");
    }

    @Test
    @DisplayName("fromFile 对不存在路径抛 IOException")
    void fromFile_不存在路径抛异常(@TempDir Path tmp) {
        Path missing = tmp.resolve("ghost.txt");
        assertThatThrownBy(() -> ToolArtifact.fromFile(missing))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("fromFile 对 null 路径抛 IllegalArgumentException")
    void fromFile_null路径抛异常() {
        assertThatThrownBy(() -> ToolArtifact.fromFile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    @DisplayName("紧凑构造器：path 为空抛异常")
    void 构造器_path为空抛异常() {
        assertThatThrownBy(() -> new ToolArtifact("", "x.txt", "text/plain", 1L, ArtifactKind.FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new ToolArtifact(null, "x.txt", "text/plain", 1L, ArtifactKind.FILE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("紧凑构造器：fileName 为空抛异常")
    void 构造器_fileName为空抛异常() {
        assertThatThrownBy(() -> new ToolArtifact("/tmp/x.txt", "  ", "text/plain", 1L, ArtifactKind.FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName");
    }

    @Test
    @DisplayName("紧凑构造器：size 为负抛异常")
    void 构造器_size为负抛异常() {
        assertThatThrownBy(() -> new ToolArtifact("/tmp/x.txt", "x.txt", "text/plain", -1L, ArtifactKind.FILE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    @DisplayName("紧凑构造器：mimeType 缺省回退 application/octet-stream")
    void 构造器_mimeType缺省回退() {
        ToolArtifact a = new ToolArtifact("/tmp/x.bin", "x.bin", null, 1L, null, null);
        assertThat(a.mimeType()).isEqualTo("application/octet-stream");
        assertThat(a.kind()).isEqualTo(ArtifactKind.FILE);
    }

    @Test
    @DisplayName("紧凑构造器：kind 缺省按 mimeType 推断")
    void 构造器_kind缺省按mimeType推断() {
        ToolArtifact a = new ToolArtifact("/tmp/x.png", "x.png", "image/png", 1L, null, null);
        assertThat(a.kind()).isEqualTo(ArtifactKind.IMAGE);
    }
}
