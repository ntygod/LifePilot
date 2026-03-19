package com.lifepilot.meta.infra.shell;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RingBuffer 环形缓冲区单元测试。
 *
 * @author zsg
 * @since 2026-03-20
 */
class RingBufferTest {

    @Test
    void 容量必须大于零() {
        assertThatThrownBy(() -> new RingBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空缓冲区读取返回空字符串() {
        var buf = new RingBuffer(100);
        assertThat(buf.readAll()).isEmpty();
        assertThat(buf.readIncremental()).isEmpty();
        assertThat(buf.size()).isZero();
    }

    @Test
    void 追加后可读取全部内容() {
        var buf = new RingBuffer(100);
        buf.append("hello");
        buf.append(" world");
        assertThat(buf.readAll()).isEqualTo("hello world");
        assertThat(buf.size()).isEqualTo(11);
    }

    @Test
    void 超出容量时覆盖最早内容() {
        var buf = new RingBuffer(5);
        buf.append("abcde");
        buf.append("fg");
        // 缓冲区应包含 "cdefg"
        assertThat(buf.readAll()).isEqualTo("cdefg");
        assertThat(buf.size()).isEqualTo(5);
    }

    @Test
    void 增量读取返回新内容() {
        var buf = new RingBuffer(100);
        buf.append("first");
        assertThat(buf.readIncremental()).isEqualTo("first");

        buf.append("second");
        assertThat(buf.readIncremental()).isEqualTo("second");

        // 无新内容时返回空
        assertThat(buf.readIncremental()).isEmpty();
    }

    @Test
    void 增量读取_数据被覆盖时包含提示() {
        var buf = new RingBuffer(5);
        buf.append("abc");
        buf.readIncremental(); // 读取 "abc"

        buf.append("defghij"); // 写入 7 字符，超过容量，覆盖了未读数据
        String result = buf.readIncremental();
        assertThat(result).contains("[部分输出已被覆盖]");
    }

    @Test
    void totalWritten_记录总写入量() {
        var buf = new RingBuffer(5);
        buf.append("abcdefgh"); // 8 字符
        assertThat(buf.totalWritten()).isEqualTo(8);
        assertThat(buf.size()).isEqualTo(5); // 容量限制
    }

    @Test
    void append_null和空字符串不影响缓冲区() {
        var buf = new RingBuffer(100);
        buf.append(null);
        buf.append("");
        assertThat(buf.size()).isZero();
        assertThat(buf.totalWritten()).isZero();
    }
}
