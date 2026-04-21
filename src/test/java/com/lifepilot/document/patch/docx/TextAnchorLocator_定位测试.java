package com.lifepilot.document.patch.docx;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextAnchorLocator 定位测试 —— 覆盖唯一命中 / 0 命中 / 多命中 / 跨 run 场景。
 *
 * @author zsg
 * @since 2026-04-21
 */
class TextAnchorLocator_定位测试 {

    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/document/sample-contract.docx");
    private static final Path STYLES = Path.of("src/test/resources/fixtures/document/sample-with-styles.docx");

    private final TextAnchorLocator locator = new TextAnchorLocator();

    @Test
    @DisplayName("唯一命中返回 ParagraphRunRange")
    void 唯一命中返回范围() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            Optional<ParagraphRunRange> r = locator.locate(doc, "风险如下：", "付款期限 30 天", "，若超期");
            assertThat(r).isPresent();
            assertThat(r.get().paragraphIndex()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("0 命中返回 empty")
    void 零命中返回empty() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            Optional<ParagraphRunRange> r = locator.locate(doc, "", "不存在的文字片段 XYZ", "");
            assertThat(r).isEmpty();
        }
    }

    @Test
    @DisplayName("多命中返回 empty 防误改")
    void 多命中返回empty() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            // 不加任何 context，"第" 在多个标题里出现 → 多命中
            Optional<ParagraphRunRange> r = locator.locate(doc, "", "第", "");
            assertThat(r).isEmpty();
        }
    }

    @Test
    @DisplayName("跨 run 的 target 能正确定位")
    void 跨run的target能定位() throws Exception {
        try (InputStream in = Files.newInputStream(STYLES);
             XWPFDocument doc = new XWPFDocument(in)) {
            // sample-with-styles 第一段三 run：["开头段 ", "重要内容", " 结尾段"]
            // target "段 重要" 跨 run 1-2
            Optional<ParagraphRunRange> r = locator.locate(doc, "开头", "段 重要", "内容");
            assertThat(r).isPresent();
            var range = r.get();
            assertThat(range.startRunIndex()).isZero();
            assertThat(range.endRunIndex()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("locateParagraphIndexByFullText 精确匹配")
    void 按段落全文精确匹配() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            int idx = locator.locateParagraphIndexByFullText(doc, "第三章 违约责任");
            assertThat(idx).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("按段落全文找不到返回 -1")
    void 段落全文找不到返回负一() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            int idx = locator.locateParagraphIndexByFullText(doc, "不存在的段落");
            assertThat(idx).isEqualTo(-1);
        }
    }
}
