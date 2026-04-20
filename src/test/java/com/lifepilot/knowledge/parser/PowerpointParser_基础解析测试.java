package com.lifepilot.knowledge.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PowerpointParser 基础解析测试 —— 验证 pptx 文件按幻灯片顺序输出、
 * 每张幻灯片作为章节标题、pageCount 等于幻灯片数量、空文件安全、
 * 非法格式抛 {@link DocumentParseException}、扩展名匹配等能力。
 *
 * @author zsg
 * @since 2026-04-20
 */
class PowerpointParser_基础解析测试 {

    @Test
    void supportedExtensions_返回_pptx() {
        assertThat(new PowerpointParser().supportedExtensions()).containsExactly("pptx");
    }

    @Test
    void 解析每张幻灯片的文本占位符(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("deck.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide1 = ppt.createSlide();
            XSLFTextBox tb1 = slide1.createTextBox();
            tb1.setAnchor(new Rectangle(50, 50, 400, 100));
            tb1.setText("海豚登月计划 — 首张幻灯片");

            XSLFSlide slide2 = ppt.createSlide();
            XSLFTextBox tb2 = slide2.createTextBox();
            tb2.setAnchor(new Rectangle(50, 50, 400, 100));
            tb2.setText("第二张:执行路线");

            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.text()).contains("海豚登月计划");
        assertThat(result.text()).contains("首张幻灯片");
        assertThat(result.text()).contains("第二张");
        assertThat(result.text()).contains("执行路线");
    }

    @Test
    void 幻灯片索引作为章节标题输出(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("titled.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox tb = slide.createTextBox();
            tb.setAnchor(new Rectangle(50, 50, 400, 100));
            tb.setText("内容 A");
            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        // 第 1 张幻灯片应该有一个 "幻灯片 1" 或类似章节标识
        assertThat(result.text()).containsAnyOf("幻灯片 1", "幻灯片1", "Slide 1");
    }

    @Test
    void 空_pptx_返回空文本但不抛异常(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("empty.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow();
             OutputStream out = Files.newOutputStream(file)) {
            ppt.write(out);
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.text()).isNotNull();
        assertThat(result.metadata()).isNotNull();
    }

    @Test
    void 元数据_pageCount_等于幻灯片数量(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("threepages.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.createSlide();
            ppt.createSlide();
            ppt.createSlide();
            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.metadata().pageCount()).isEqualTo(3);
    }

    @Test
    void 不是有效_pptx_文件抛_DocumentParseException(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("fake.pptx");
        Files.writeString(file, "not a real pptx");

        assertThatThrownBy(() -> new PowerpointParser().parse(file))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    void canParse_按扩展名匹配(@TempDir Path tmp) throws IOException {
        PowerpointParser parser = new PowerpointParser();
        Path pptx = tmp.resolve("a.pptx");
        Path xlsx = tmp.resolve("a.xlsx");
        Files.createFile(pptx);
        Files.createFile(xlsx);

        assertThat(parser.canParse(pptx)).isTrue();
        assertThat(parser.canParse(xlsx)).isFalse();
    }
}
