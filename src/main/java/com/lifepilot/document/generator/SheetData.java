package com.lifepilot.document.generator;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Excel 工作表中间模型。
 *
 * <p>Tool 层从 ToolInput 反序列化为 SheetData 列表传给 ExcelGenerator,
 * 内部结构 LLM 可见（工具 schema 直接描述此 record 字段）。</p>
 *
 * <p>Compact constructor 对 rows 做防御性拷贝（外层不可变、保留 null 行语义）,
 * 对 headers 调用 {@link List#copyOf} 做不可变拷贝（headers 不允许 null 元素）。
 * name 由 POI {@code WorkbookUtil.createSafeSheetName} 在生成器中兜底，不在此做校验。</p>
 *
 * @param name    工作表名称（非空；含非法字符或超长由 POI 安全化）
 * @param headers 表头行（可为 null / 空列表 → 不输出表头行；headers 不允许含 null 元素）
 * @param rows    数据行，每行是单元格值列表。单元格可为 String / Number / Boolean / null
 *                （POI DataFormatter 按类型输出显示值）。允许外层 null（按空列表处理）,
 *                允许单行为 null（POI 规则：行可存在但空）
 * @author zsg
 * @since 2026-04-20
 */
public record SheetData(
        String name,
        @Nullable List<String> headers,
        List<List<Object>> rows
) {

    /**
     * 防御性拷贝：外层不可变，保留 null 行（供 {@code StructuredDataToXlsxGenerator} null 行守卫消费）。
     */
    public SheetData {
        if (rows == null) {
            rows = List.of();
        } else {
            // 允许内部行为 null（POI 允许空行占位），仅保护外层 list 不被调用方修改
            rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
        if (headers != null) {
            // headers 元素不允许 null（表头必须有值）；List.copyOf 会在遇到 null 时抛 NPE
            headers = List.copyOf(headers);
        }
    }

    /**
     * 简化构造：无表头工作表。
     */
    public static SheetData of(String name, List<List<Object>> rows) {
        return new SheetData(name, null, rows);
    }

    /**
     * 简化构造：有表头工作表。
     */
    public static SheetData withHeaders(String name, List<String> headers, List<List<Object>> rows) {
        return new SheetData(name, headers, rows);
    }
}
