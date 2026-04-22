package com.lifepilot.document.generator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化数据 → xlsx 生成器，基于 Apache POI XSSF。
 *
 * <p>按 SheetData 列表顺序建工作表，先写 headers（如有）再按 rows 展开单元格。
 * 单元格类型：Number 走 numeric cell，Boolean 走 boolean cell，其它包括 null 转字符串，
 * null 输出空串。不做样式 / 公式 / 合并单元格 —— Phase 2A 基础深度，按需 Phase 2+ 扩展。</p>
 *
 * <p>工作表名称通过 {@link WorkbookUtil#createSafeSheetName(String)} 安全化
 * （替换 {@code \ / ? * [ ] :} 等非法字符、截断到 31 字符），并对重名自动追加
 * {@code (2) (3) ...} 后缀以规避 POI 的重名异常。</p>
 *
 * <p>sheets 为 null 或空列表时生成含 {@code Sheet1} 占位的最小可打开 xlsx
 * （XSSFWorkbook 规则：workbook 至少 1 张 sheet）。rows 中单行为 null 时
 * 仍创建空行占位（保留行号），不 skip。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class StructuredDataToXlsxGenerator implements ExcelGenerator {

    private static final Logger log = LoggerFactory.getLogger(StructuredDataToXlsxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(List<SheetData> sheets) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (sheets == null || sheets.isEmpty()) {
                // XSSFWorkbook 必须至少 1 张工作表才可打开
                wb.createSheet("Sheet1");
                wb.write(out);
                return out.toByteArray();
            }

            // 已使用的工作表名（安全化+去重后）集合，用于对重名追加 (2) (3) ...
            Set<String> usedNames = new HashSet<>();

            for (SheetData data : sheets) {
                String safeName = sanitizeSheetName(data.name(), usedNames);
                Sheet sheet = wb.createSheet(safeName);
                int rowIdx = 0;

                if (data.headers() != null && !data.headers().isEmpty()) {
                    Row header = sheet.createRow(rowIdx++);
                    for (int c = 0; c < data.headers().size(); c++) {
                        header.createCell(c).setCellValue(data.headers().get(c));
                    }
                }

                if (data.rows() != null) {
                    for (List<Object> rowValues : data.rows()) {
                        // null 行守卫：保留行号占位（空行），不 skip —— 与 SheetData Javadoc 约定一致
                        Row row = sheet.createRow(rowIdx++);
                        if (rowValues == null) {
                            continue;
                        }
                        for (int c = 0; c < rowValues.size(); c++) {
                            Cell cell = row.createCell(c);
                            Object value = rowValues.get(c);
                            setCellValue(cell, value);
                        }
                    }
                }
            }

            wb.write(out);
            log.info("StructuredDataToXlsx 生成完成：sheets={}, outputBytes={}",
                    sheets.size(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("xlsx 生成失败：" + e.getMessage(), e);
        }
    }

    /**
     * 工作表名安全化。
     *
     * <p>先调 {@link WorkbookUtil#createSafeSheetName} 处理非法字符和长度限制，
     * 再对重名依次尝试 {@code (2) (3) ...} 后缀直到唯一。追加后缀时优先保证
     * 不超过 POI 的 31 字符限制，必要时截断 base 部分。</p>
     *
     * @param raw  原始名（LLM tool input 可能含 {@code \ / ? * [ ] :} 或过长）
     * @param used 已使用名集合（就地更新：将最终选定的名加入）
     * @return 安全且唯一的工作表名
     */
    private String sanitizeSheetName(String raw, Set<String> used) {
        String base = WorkbookUtil.createSafeSheetName(raw);
        if (used.add(base)) {
            return base;
        }
        // 重名：追加 (2) (3) ... 后缀，必要时截断 base 保证总长 ≤ 31
        for (int i = 2; ; i++) {
            String suffix = " (" + i + ")";
            int maxBaseLen = 31 - suffix.length();
            String trimmedBase = base.length() > maxBaseLen ? base.substring(0, maxBaseLen) : base;
            String candidate = trimmedBase + suffix;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(value.toString());
        }
    }
}
