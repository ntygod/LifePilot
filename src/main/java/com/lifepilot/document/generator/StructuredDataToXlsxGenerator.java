package com.lifepilot.document.generator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 结构化数据 → xlsx 生成器，基于 Apache POI XSSF。
 *
 * <p>按 SheetData 列表顺序建工作表，先写 headers（如有）再按 rows 展开单元格。
 * 单元格类型：Number 走 numeric cell，Boolean 走 boolean cell，其它包括 null 转字符串，
 * null 输出空串。不做样式 / 公式 / 合并单元格 —— Phase 2A 基础深度，按需 Phase 2+ 扩展。</p>
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

            for (SheetData data : sheets) {
                Sheet sheet = wb.createSheet(data.name());
                int rowIdx = 0;

                if (data.headers() != null && !data.headers().isEmpty()) {
                    Row header = sheet.createRow(rowIdx++);
                    for (int c = 0; c < data.headers().size(); c++) {
                        header.createCell(c).setCellValue(data.headers().get(c));
                    }
                }

                if (data.rows() != null) {
                    for (List<Object> rowValues : data.rows()) {
                        Row row = sheet.createRow(rowIdx++);
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
