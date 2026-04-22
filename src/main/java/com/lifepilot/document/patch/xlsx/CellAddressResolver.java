package com.lifepilot.document.patch.xlsx;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A1 notation 地址解析工具。
 *
 * <p>统一处理：剥掉 {@code $} 绝对引用前缀、upper-case 列字母、拒绝带 {@code !} 的
 * sheet-prefixed 写法（本项目把 sheet 放在独立字段里，不塞 {@code Sheet1!B5}）。</p>
 *
 * <p>解析失败返回 {@link Optional#empty()}；调用方转成 {@code invalid_cell_address} /
 * {@code invalid_range} 错误。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public final class CellAddressResolver {

    private static final Pattern CELL_PATTERN = Pattern.compile("^\\$?[A-Z]+\\$?[0-9]+$");
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^\\$?[A-Z]+\\$?[0-9]+:\\$?[A-Z]+\\$?[0-9]+$");

    private CellAddressResolver() {}

    /** 解析 "B5" / "$B$5" / "aa12" 等；含 "!" 或格式非法返回空。 */
    public static Optional<CellReference> parseCell(String address) {
        if (address == null) return Optional.empty();
        String cleaned = address.trim().toUpperCase(Locale.ROOT).replace("$", "");
        if (!CELL_PATTERN.matcher(cleaned).matches()) return Optional.empty();
        try {
            CellReference ref = new CellReference(cleaned);
            if (ref.getRow() < 0 || ref.getCol() < 0) return Optional.empty();
            return Optional.of(ref);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 解析 "B2:D4" / "$B$2:$D$4"；左右颠倒（如 "D4:B2"）按 POI 规则自动规范化。 */
    public static Optional<CellRangeAddress> parseRange(String range) {
        if (range == null) return Optional.empty();
        String cleaned = range.trim().toUpperCase(Locale.ROOT).replace("$", "");
        if (!RANGE_PATTERN.matcher(cleaned).matches()) return Optional.empty();
        try {
            CellRangeAddress addr = CellRangeAddress.valueOf(cleaned);
            if (addr.getFirstRow() < 0 || addr.getFirstColumn() < 0) return Optional.empty();
            if (addr.getLastRow() < addr.getFirstRow()
                    || addr.getLastColumn() < addr.getFirstColumn()) {
                return Optional.empty();
            }
            return Optional.of(addr);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
