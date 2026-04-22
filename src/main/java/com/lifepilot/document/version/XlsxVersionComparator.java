package com.lifepilot.document.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 任意两个 xlsx 版本之间的 cell 级 diff 计算 —— P1-7。
 *
 * <p>算法：遍历两 workbook 相同 sheet 名的所有 cell，用 DataFormatter 取显示值对比；
 * 不一致即生成 update_cell change。新增行/删除行通过"一侧存在另一侧不存在"检测。</p>
 *
 * <p>简化：不对比公式层面（公式值变化但公式字符串相同视为"值改变"）。合并单元格也
 * 按 anchor 对比。充分够用于前端版本历史的"看看这两版差在哪"。</p>
 *
 * @author zsg
 * @since 2026-04-22
 */
public class XlsxVersionComparator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DataFormatter FORMATTER = new DataFormatter();

    public String compare(String documentId, int fromVersion, int toVersion,
                          Path fromFile, Path toFile) throws IOException {
        List<Map<String, Object>> changes = new ArrayList<>();
        try (InputStream in1 = Files.newInputStream(fromFile);
             InputStream in2 = Files.newInputStream(toFile);
             XSSFWorkbook wb1 = new XSSFWorkbook(in1);
             XSSFWorkbook wb2 = new XSSFWorkbook(in2)) {

            Set<String> sheetNames = new HashSet<>();
            for (int i = 0; i < wb1.getNumberOfSheets(); i++) sheetNames.add(wb1.getSheetName(i));
            for (int i = 0; i < wb2.getNumberOfSheets(); i++) sheetNames.add(wb2.getSheetName(i));

            for (String name : sheetNames) {
                Sheet s1 = wb1.getSheet(name);
                Sheet s2 = wb2.getSheet(name);
                compareSheet(name, s1, s2, changes);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("mime", "xlsx");
        root.put("summary", changes.isEmpty()
                ? "两版本内容相同"
                : "共 " + changes.size() + " 处 cell 差异");
        root.put("changes", changes);

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IOException("xlsx 版本对比序列化失败", e);
        }
    }

    private void compareSheet(String sheetName, Sheet s1, Sheet s2, List<Map<String, Object>> changes) {
        int lastRow1 = s1 == null ? -1 : s1.getLastRowNum();
        int lastRow2 = s2 == null ? -1 : s2.getLastRowNum();
        int maxRow = Math.max(lastRow1, lastRow2);

        for (int r = 0; r <= maxRow; r++) {
            Row r1 = s1 == null ? null : s1.getRow(r);
            Row r2 = s2 == null ? null : s2.getRow(r);
            int lastCol1 = r1 == null ? -1 : r1.getLastCellNum() - 1;
            int lastCol2 = r2 == null ? -1 : r2.getLastCellNum() - 1;
            int maxCol = Math.max(lastCol1, lastCol2);

            for (int c = 0; c <= maxCol; c++) {
                Cell c1 = r1 == null ? null : r1.getCell(c);
                Cell c2 = r2 == null ? null : r2.getCell(c);
                String v1 = c1 == null ? "" : FORMATTER.formatCellValue(c1);
                String v2 = c2 == null ? "" : FORMATTER.formatCellValue(c2);
                if (!v1.equals(v2)) {
                    changes.add(buildCellChange(sheetName, r, c, v1, v2));
                }
            }
        }
    }

    private Map<String, Object> buildCellChange(String sheet, int row, int col, String before, String after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("patch_id", UUID.randomUUID().toString());
        change.put("op", "update_cell");
        change.put("sheet", sheet);
        change.put("cell", new CellReference(row, col).formatAsString());
        change.put("row", row + 1);
        change.put("col", col + 1);
        List<Map<String, Object>> segments = new ArrayList<>();
        if (!before.isEmpty()) segments.add(Map.of("type", "delete", "text", before));
        if (!after.isEmpty()) segments.add(Map.of("type", "insert", "text", after));
        change.put("segments", segments);
        return change;
    }
}
