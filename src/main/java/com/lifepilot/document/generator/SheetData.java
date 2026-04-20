package com.lifepilot.document.generator;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Excel 工作表中间模型。
 *
 * <p>Tool 层从 ToolInput 反序列化为 SheetData 列表传给 ExcelGenerator,
 * 内部结构 LLM 可见（工具 schema 直接描述此 record 字段）。</p>
 *
 * @param name    工作表名称（非空）
 * @param headers 表头行（可为 null / 空列表 → 不输出表头行）
 * @param rows    数据行，每行是单元格值列表。单元格可为 String / Number / Boolean / null
 *                （POI DataFormatter 按类型输出显示值）
 * @author zsg
 * @since 2026-04-20
 */
public record SheetData(
        String name,
        @Nullable List<String> headers,
        List<List<Object>> rows
) {

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
