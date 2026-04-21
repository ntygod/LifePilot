package com.lifepilot.document.patch.xlsx;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CellAddressResolver 解析路径测试 —— A1 / range / 非法输入。
 *
 * @author zsg
 * @since 2026-04-21
 */
class CellAddressResolver_解析测试 {

    @Test
    void 单元格_标准A1解析成功() {
        Optional<CellReference> ref = CellAddressResolver.parseCell("B5");
        assertThat(ref).isPresent();
        assertThat(ref.get().getRow()).isEqualTo(4);
        assertThat((int) ref.get().getCol()).isEqualTo(1);
    }

    @Test
    void 单元格_小写和绝对引用被规范化() {
        assertThat(CellAddressResolver.parseCell("b5")).isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getRow()).isEqualTo(4);
                    assertThat((int) r.getCol()).isEqualTo(1);
                });
        assertThat(CellAddressResolver.parseCell("$B$5")).isPresent();
        assertThat(CellAddressResolver.parseCell("$AA$12")).isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getRow()).isEqualTo(11);
                    assertThat((int) r.getCol()).isEqualTo(26);
                });
    }

    @Test
    void 单元格_非法格式返回空() {
        assertThat(CellAddressResolver.parseCell(null)).isEmpty();
        assertThat(CellAddressResolver.parseCell("")).isEmpty();
        assertThat(CellAddressResolver.parseCell("5B")).isEmpty();
        assertThat(CellAddressResolver.parseCell("B")).isEmpty();
        assertThat(CellAddressResolver.parseCell("123")).isEmpty();
        assertThat(CellAddressResolver.parseCell("Sheet1!B5")).isEmpty();
        assertThat(CellAddressResolver.parseCell("B 5")).isEmpty();
    }

    @Test
    void 区域_标准解析成功() {
        Optional<CellRangeAddress> r = CellAddressResolver.parseRange("B2:D4");
        assertThat(r).isPresent();
        assertThat(r.get().getFirstRow()).isEqualTo(1);
        assertThat(r.get().getFirstColumn()).isEqualTo(1);
        assertThat(r.get().getLastRow()).isEqualTo(3);
        assertThat(r.get().getLastColumn()).isEqualTo(3);
    }

    @Test
    void 区域_绝对引用和小写被规范化() {
        assertThat(CellAddressResolver.parseRange("$b$2:$d$4")).isPresent();
    }

    @Test
    void 区域_非法格式返回空() {
        assertThat(CellAddressResolver.parseRange(null)).isEmpty();
        assertThat(CellAddressResolver.parseRange("B2")).isEmpty();
        assertThat(CellAddressResolver.parseRange("B2:")).isEmpty();
        assertThat(CellAddressResolver.parseRange("B2-D4")).isEmpty();
        assertThat(CellAddressResolver.parseRange("Sheet1!B2:D4")).isEmpty();
    }
}
