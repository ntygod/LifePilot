package com.lifepilot.interaction.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inferMimeFromFileName 单测 —— 校正 connector 上报不准的 MIME。
 *
 * <p>冒烟暴露：飞书对 xlsx 文件报 {@code application/octet-stream}，导致文档白名单
 * 匹配失败、hint 注入失效。按扩展名兜底是数据层修复（修根因而非在 prompt 补丁）。</p>
 *
 * @author zsg
 * @since 2026-04-22
 */
class ChannelRuntimeIngressService_Mime推断测试 {

    @Test
    @DisplayName("connector 明确报了非 octet-stream MIME，保持原值不干预")
    void 已知MIME不干预() {
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName("a.xlsx", "image/png"))
                .isEqualTo("image/png");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName("a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    @DisplayName("飞书对 xlsx 报 octet-stream 时按扩展名校正为真实 spreadsheetml.sheet")
    void xlsx校正() {
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "report.xlsx", "application/octet-stream"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    @DisplayName("docx / pptx / pdf 也按扩展名校正")
    void 其他常见文档校正() {
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.docx", "application/octet-stream"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.pptx", "application/octet-stream"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.pdf", ""))
                .isEqualTo("application/pdf");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.md", null))
                .isEqualTo("text/markdown");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.csv", "application/octet-stream"))
                .isEqualTo("text/csv");
    }

    @Test
    @DisplayName("未知扩展名 + octet-stream：保持 octet-stream")
    void 未知扩展名不变() {
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "a.xyz", "application/octet-stream"))
                .isEqualTo("application/octet-stream");
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "noext", "application/octet-stream"))
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("大写扩展名一样命中")
    void 大小写不敏感() {
        assertThat(ChannelRuntimeIngressService.inferMimeFromFileName(
                "REPORT.XLSX", "application/octet-stream"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
