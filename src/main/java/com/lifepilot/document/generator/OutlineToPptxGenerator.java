package com.lifepilot.document.generator;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 幻灯片大纲 → pptx 生成器,基于 Apache POI XSLF。
 *
 * <p>每个 SlideData 渲染为一张幻灯片:顶部文本框放标题（如有）,下方文本框放要点列表
 * （每个要点一行,加圆点前缀）,备注写入幻灯片的 notes 区域。不做主题 / 动画 / 图片 / 图表
 * —— Phase 2B 基础深度,按需 Phase 2+ 扩展。</p>
 *
 * <p>边界行为:</p>
 * <ul>
 *   <li>slides 为 null 或空列表 → 生成零幻灯片的合法 pptx（XMLSlideShow 允许空演示文稿,
 *   和 XSSFWorkbook 至少需要 1 张 sheet 的规则不同）</li>
 *   <li>title 为 null / 空白 → 不渲染标题框</li>
 *   <li>bullets 为空 → 不渲染要点框</li>
 *   <li>notes 为 null / 空白 → 不写入备注区（不触发 notes slide 创建）</li>
 *   <li>一张完全空白的 SlideData（title=null, bullets=空, notes=null）仍创建一张空白幻灯片</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class OutlineToPptxGenerator implements PowerpointGenerator {

    private static final Logger log = LoggerFactory.getLogger(OutlineToPptxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(List<SlideData> slides) {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (slides == null || slides.isEmpty()) {
                // XMLSlideShow 允许零幻灯片演示文稿,直接写出
                ppt.write(out);
                return out.toByteArray();
            }

            int slideIdx = 0;
            for (SlideData data : slides) {
                XSLFSlide slide = ppt.createSlide();

                if (data.title() != null && !data.title().isBlank()) {
                    XSLFTextBox titleBox = slide.createTextBox();
                    titleBox.setAnchor(new Rectangle(40, 30, 620, 60));
                    titleBox.setText(data.title());
                }

                if (!data.bullets().isEmpty()) {
                    XSLFTextBox bulletBox = slide.createTextBox();
                    bulletBox.setAnchor(new Rectangle(40, 110, 620, 400));
                    boolean first = true;
                    for (String bullet : data.bullets()) {
                        // 首个要点复用自动生成的段落;后续通过 addNewTextParagraph 追加
                        var paragraph = first
                                ? bulletBox.getTextParagraphs().get(0)
                                : bulletBox.addNewTextParagraph();
                        var run = paragraph.addNewTextRun();
                        run.setText("• " + bullet);
                        first = false;
                    }
                }

                if (data.notes() != null && !data.notes().isBlank()) {
                    // getNotesSlide 若 notes slide 不存在则创建,保证 notes 区域可写
                    XSLFNotes notes = ppt.getNotesSlide(slide);
                    writeNotesText(notes, data.notes(), slideIdx);
                }
                slideIdx++;
            }

            ppt.write(out);
            log.info("OutlineToPptx 生成完成：slides={}，outputBytes={}",
                    slides.size(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("pptx 生成失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把备注文本写入 notes slide 的第一个文本占位符。
     *
     * <p>POI 创建 notes slide 时会自带若干占位符形状（幻灯片编号、备注文本等）,
     * 这里遍历找到首个 {@link XSLFTextShape} 并覆盖其文本内容。找不到则不写入
     * （理论上 notes slide 总会有文本形状,但防御性处理避免 NPE）。</p>
     *
     * @param notes    当前幻灯片的 notes slide
     * @param text     备注文本
     * @param slideIdx 幻灯片索引（从 0 开始）,仅用于 WARN 日志定位
     */
    private void writeNotesText(XSLFNotes notes, String text, int slideIdx) {
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                textShape.clearText();
                textShape.setText(text);
                return;
            }
        }
        log.warn("Notes slide 未找到可写文本形状，备注丢弃：slideIdx={}", slideIdx);
    }
}
