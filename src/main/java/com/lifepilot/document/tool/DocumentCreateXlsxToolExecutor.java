package com.lifepilot.document.tool;

import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.SheetData;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * document.create_xlsx 工具执行体。
 *
 * <p>流程框架继承自 {@link AbstractDocumentCreateToolExecutor}, 本类只负责:</p>
 * <ul>
 *   <li>声明 MIME / 扩展名 (xlsx 专属);</li>
 *   <li>解析 {@code sheets} 参数 ({@link #parseSheets} 把 Map 反序列化为 {@link SheetData} 列表)
 *       并委托 {@link ExcelGenerator#generate(List)} 生成字节。</li>
 * </ul>
 *
 * <p>落盘 / 入库 / 返回结构由父类统一处理, 保持与 docx / pptx 行为对称。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateXlsxToolExecutor extends AbstractDocumentCreateToolExecutor {

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String EXT = ".xlsx";

    private final ExcelGenerator generator;

    public DocumentCreateXlsxToolExecutor(ExcelGenerator generator,
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
        // sheets 参数缺失 / 类型不匹配时 ToolInput.getParam 抛 IllegalArgumentException, 由父类捕获
        List<?> sheetsRaw = input.getParam("sheets", List.class);
        // parseSheets 非法格式也抛 IllegalArgumentException, 统一由父类包成 "参数校验失败：..." 返回
        List<SheetData> sheets = parseSheets(sheetsRaw);
        return generator.generate(sheets);
    }

    /**
     * 把 Map 反序列化为 {@link SheetData} 列表。期望格式:
     * <pre>
     * [{ "name": "工作表名", "headers": ["列A", "列B"], "rows": [[val1, val2], ...] }]
     * </pre>
     *
     * <p>headers 可选 (缺失或空数组 → 不输出表头), rows 可选 (缺失 → 空工作表)。
     * 单元格值保留 Number / Boolean / String 类型透传给 POI, 生成器按类型写入相应 cell type。</p>
     */
    @SuppressWarnings("unchecked")
    private List<SheetData> parseSheets(List<?> raw) {
        List<SheetData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("sheet 项必须是 object，收到：" + item);
            }
            Object nameObj = map.get("name");
            if (!(nameObj instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("sheet.name 必须是非空字符串");
            }

            List<String> headers = null;
            Object headersObj = map.get("headers");
            if (headersObj instanceof List<?> headersRaw && !headersRaw.isEmpty()) {
                headers = new ArrayList<>();
                for (Object h : headersRaw) {
                    headers.add(h == null ? "" : h.toString());
                }
            }

            List<List<Object>> rows = new ArrayList<>();
            Object rowsObj = map.get("rows");
            if (rowsObj instanceof List<?> rowsRaw) {
                for (Object rowObj : rowsRaw) {
                    if (!(rowObj instanceof List<?> rowRaw)) {
                        throw new IllegalArgumentException("sheet.rows 项必须是 array");
                    }
                    rows.add(new ArrayList<>((List<Object>) rowRaw));
                }
            }

            result.add(new SheetData(name, headers, rows));
        }
        return result;
    }
}
