package com.lifepilot.meta.infra.shell;

/**
 * 线程安全的环形字符缓冲区 — 用于后台进程输出存储。
 *
 * <p>当缓冲区满时自动覆盖最早的内容，保证最新输出始终可读。
 * 支持增量读取（自上次读取位置起的新内容）。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class RingBuffer {

    private final char[] buffer;
    private final int capacity;

    /** 下一个写入位置。 */
    private int writePos = 0;

    /** 总写入字符数（用于判断是否发生过环绕）。 */
    private long totalWritten = 0;

    /** 上次增量读取的位置（基于 totalWritten）。 */
    private long lastReadPos = 0;

    /**
     * 创建指定容量的环形缓冲区。
     *
     * @param capacity 缓冲区容量（字符数）
     */
    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("缓冲区容量必须大于 0: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new char[capacity];
    }

    /**
     * 向缓冲区追加内容，超出容量时覆盖最早的内容。
     *
     * @param text 要追加的文本
     */
    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        char[] chars = text.toCharArray();
        for (char c : chars) {
            buffer[writePos] = c;
            writePos = (writePos + 1) % capacity;
            totalWritten++;
        }
    }

    /**
     * 读取缓冲区全部有效内容。
     *
     * @return 缓冲区中的全部文本（按写入顺序）
     */
    public synchronized String readAll() {
        if (totalWritten == 0) {
            return "";
        }
        int validLength = (int) Math.min(totalWritten, capacity);
        var sb = new StringBuilder(validLength);
        // 如果发生过环绕，从 writePos 开始读（最早的有效数据）
        int startPos = totalWritten > capacity ? writePos : 0;
        for (int i = 0; i < validLength; i++) {
            sb.append(buffer[(startPos + i) % capacity]);
        }
        return sb.toString();
    }

    /**
     * 增量读取 — 返回自上次调用以来新写入的内容。
     *
     * <p>如果新内容超过缓冲区容量（中间有数据被覆盖），返回当前缓冲区全部内容并附加丢失提示。</p>
     *
     * @return 自上次读取以来的新内容
     */
    public synchronized String readIncremental() {
        if (totalWritten == 0 || lastReadPos >= totalWritten) {
            return "";
        }
        long unread = totalWritten - lastReadPos;
        // 如果未读数据超过容量，说明有数据被覆盖
        if (unread > capacity) {
            lastReadPos = totalWritten;
            return "[部分输出已被覆盖]\n" + readAll();
        }
        int length = (int) unread;
        var sb = new StringBuilder(length);
        // 计算起始读取位置
        int startPos = (int) ((writePos - length + capacity) % capacity);
        if (startPos < 0) startPos += capacity;
        for (int i = 0; i < length; i++) {
            sb.append(buffer[(startPos + i) % capacity]);
        }
        lastReadPos = totalWritten;
        return sb.toString();
    }

    /** 获取缓冲区容量。 */
    public int capacity() {
        return capacity;
    }

    /** 获取当前有效内容长度。 */
    public synchronized int size() {
        return (int) Math.min(totalWritten, capacity);
    }

    /** 获取总写入字符数。 */
    public synchronized long totalWritten() {
        return totalWritten;
    }
}
