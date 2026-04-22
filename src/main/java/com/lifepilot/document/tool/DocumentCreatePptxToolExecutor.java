package com.lifepilot.document.tool;

import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.SlideData;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * document.create_pptx 工具执行体。
 *
 * <p>流程框架继承自 {@link AbstractDocumentCreateToolExecutor}, 本类只负责:</p>
 * <ul>
 *   <li>声明 MIME / 扩展名 (pptx 专属);</li>
 *   <li>解析 {@code slides} 参数 ({@link #parseSlides} 把 Map 反序列化为 {@link SlideData} 列表)
 *       并委托 {@link PowerpointGenerator#generate(List)} 生成字节。</li>
 * </ul>
 *
 * <p>落盘 / 入库 / 返回结构由父类统一处理, 保持与 docx / xlsx 行为对称。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreatePptxToolExecutor extends AbstractDocumentCreateToolExecutor {

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String EXT = ".pptx";

    private final PowerpointGenerator generator;

    public DocumentCreatePptxToolExecutor(PowerpointGenerator generator,
                                          SessionDocumentRepository sessionDocumentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        super(sessionDocumentRepository, attachmentRepository, storageDir);
        this.generator = generator;
    }

    @Override
    protected String mime() {
        return MIME;
    }

    @Override
    protected String ext() {
        return EXT;
    }

    @Override
    protected byte[] generateBytes(ToolInput input) {
        // slides 参数缺失 / 类型不匹配时 ToolInput.getParam 抛 IllegalArgumentException, 由父类捕获
        List<?> slidesRaw = input.getParam("slides", List.class);
        // parseSlides 非法格式也抛 IllegalArgumentException, 统一由父类包成 "参数校验失败：..." 返回
        List<SlideData> slides = parseSlides(slidesRaw);
        return generator.generate(slides);
    }

    /**
     * 把 Map 反序列化为 {@link SlideData} 列表。期望格式:
     * <pre>
     * [{ "title": "首页", "bullets": ["要点 A", "要点 B"], "notes": "讲稿" }]
     * </pre>
     *
     * <p>title / notes 可选 (非 String 或缺失时降级为 null → 生成器不渲染对应区域);
     * bullets 可选 (缺失或非 List → 空列表, 生成器不渲染要点框)。
     * bullets 子元素非 String 时调用 {@code toString()} 兜底, null 归一化为空字符串
     * —— 保证 SlideData 的 compact constructor 不因 {@code List.copyOf} 遇到 null 元素 NPE。</p>
     */
    private List<SlideData> parseSlides(List<?> raw) {
        List<SlideData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("slide 项必须是 object，收到：" + item);
            }
            String title = map.get("title") instanceof String t ? t : null;
            String notes = map.get("notes") instanceof String n ? n : null;

            List<String> bullets = new ArrayList<>();
            Object bulletsObj = map.get("bullets");
            if (bulletsObj instanceof List<?> bulletsRaw) {
                for (Object b : bulletsRaw) {
                    bullets.add(b == null ? "" : b.toString());
                }
            }

            result.add(new SlideData(title, bullets, notes));
        }
        return result;
    }
}
