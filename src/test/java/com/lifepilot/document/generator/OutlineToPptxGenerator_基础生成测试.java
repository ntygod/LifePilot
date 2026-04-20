package com.lifepilot.document.generator;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineToPptxGenerator_基础生成测试 {

    private final OutlineToPptxGenerator generator = new OutlineToPptxGenerator();

    @Test
    void mimeType_返回_presentationml() {
        assertThat(generator.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void 单幻灯片含标题和要点能被回读() throws Exception {
        var slide = SlideData.of("首页", List.of("要点 A", "要点 B", "要点 C"));

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(1);
            String allText = collectSlideText(ppt, 0);
            assertThat(allText).contains("首页");
            assertThat(allText).contains("要点 A");
            assertThat(allText).contains("要点 B");
            assertThat(allText).contains("要点 C");
        }
    }

    @Test
    void 多幻灯片按顺序输出() throws Exception {
        byte[] bytes = generator.generate(List.of(
                SlideData.of("第一张", List.of("内容 1")),
                SlideData.of("第二张", List.of("内容 2"))
        ));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(2);
            assertThat(collectSlideText(ppt, 0)).contains("第一张").contains("内容 1");
            assertThat(collectSlideText(ppt, 1)).contains("第二张").contains("内容 2");
        }
    }

    @Test
    void 备注文本独立存在幻灯片备注区() throws Exception {
        var slide = SlideData.withNotes("方案", List.of("要点"), "讲稿：这里展开说明");

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            var s = ppt.getSlides().get(0);
            var notes = s.getNotes();
            assertThat(notes).isNotNull();
            String notesText = collectShapeText(notes.getShapes());
            assertThat(notesText).contains("讲稿：这里展开说明");
        }
    }

    @Test
    void 无标题无要点也能生成合法幻灯片() throws Exception {
        var slide = new SlideData(null, List.of(), null);

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(1);
        }
    }

    @Test
    void 空_slides_列表生成零幻灯片的最小可读_pptx() throws Exception {
        byte[] bytes = generator.generate(List.of());

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            // 空 slides 是合法 pptx（没有 slide）
            assertThat(ppt.getSlides()).isEmpty();
        }
    }

    private String collectSlideText(XMLSlideShow ppt, int slideIdx) {
        return collectShapeText(ppt.getSlides().get(slideIdx).getShapes());
    }

    private String collectShapeText(List<? extends XSLFShape> shapes) {
        StringBuilder sb = new StringBuilder();
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape ts) {
                sb.append(ts.getText()).append("\n");
            }
        }
        return sb.toString();
    }
}
