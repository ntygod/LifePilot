package com.lifepilot.interaction.web.a2ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A2UI 流式解析器。
 *
 * <p>有限状态机，逐字符处理 LLM 流式 token，分离普通文本和 {@code <a2ui>...</a2ui>} 块。
 * 支持 token 在标签中间断开的场景（部分标签缓冲与回退）。
 *
 * <p>状态转换：
 * <pre>
 * TEXT → OPEN_TAG（遇到 '<' 且后续可能匹配 "a2ui>"）
 * OPEN_TAG → TEXT（标签不匹配，回退缓冲）
 * OPEN_TAG → CONTENT（完整匹配 "&lt;a2ui&gt;"）
 * CONTENT → CLOSE_TAG（遇到 '<' 且后续可能匹配 "/a2ui>"）
 * CLOSE_TAG → CONTENT（标签不匹配，回退缓冲）
 * CLOSE_TAG → TEXT（完整匹配 "&lt;/a2ui&gt;"，输出 A2uiSegment）
 * </pre>
 *
 * @author zsg
 * @since 2026-03-11
 */
public class StreamingA2uiParser {

    /**
     * 解析器输出段。
     */
    public sealed interface Segment {
        /**
         * 普通文本段。
         *
         * @param text 文本内容
         */
        record TextSegment(String text) implements Segment {}

        /**
         * A2UI JSON 段（标记内的原始 JSON 字符串）。
         *
         * @param json JSON 内容
         */
        record A2uiSegment(String json) implements Segment {}
    }

    /**
     * 解析器状态。
     */
    enum State {
        /** 普通文本状态 */
        TEXT,
        /** 正在匹配开标签 {@code <a2ui>} */
        OPEN_TAG,
        /** 标签内容状态（已匹配到开标签，正在收集 JSON 内容） */
        CONTENT,
        /** 正在匹配闭标签 {@code </a2ui>} */
        CLOSE_TAG
    }

    private static final String OPEN_TAG = "<a2ui>";
    private static final String CLOSE_TAG = "</a2ui>";

    private State state = State.TEXT;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder tagBuffer = new StringBuilder();
    private final StringBuilder contentBuffer = new StringBuilder();

    /**
     * 输入一个 token，返回 0~N 个输出段。
     *
     * @param token LLM 流式输出的一个 token
     * @return 解析出的段列表（可能为空）
     */
    public List<Segment> feed(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        var segments = new ArrayList<Segment>();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            processChar(ch, segments);
        }
        return segments;
    }

    /**
     * 流结束时刷出剩余缓冲。
     *
     * <p>未闭合的标签缓冲作为普通文本输出。
     *
     * @return 剩余的段列表
     */
    public List<Segment> flush() {
        var segments = new ArrayList<Segment>();
        switch (state) {
            case TEXT -> flushTextBuffer(segments);
            case OPEN_TAG -> {
                // 未完成的开标签，回退为文本
                textBuffer.append(tagBuffer);
                tagBuffer.setLength(0);
                state = State.TEXT;
                flushTextBuffer(segments);
            }
            case CONTENT -> {
                // 未闭合的 <a2ui> 块，将开标签 + 内容作为文本输出
                textBuffer.append(OPEN_TAG);
                textBuffer.append(contentBuffer);
                contentBuffer.setLength(0);
                state = State.TEXT;
                flushTextBuffer(segments);
            }
            case CLOSE_TAG -> {
                // 未完成的闭标签，将开标签 + 内容 + 部分闭标签作为文本输出
                textBuffer.append(OPEN_TAG);
                textBuffer.append(contentBuffer);
                textBuffer.append(tagBuffer);
                contentBuffer.setLength(0);
                tagBuffer.setLength(0);
                state = State.TEXT;
                flushTextBuffer(segments);
            }
        }
        return segments;
    }

    /**
     * 重置状态以复用实例。
     */
    public void reset() {
        state = State.TEXT;
        textBuffer.setLength(0);
        tagBuffer.setLength(0);
        contentBuffer.setLength(0);
    }

    // ---- 内部方法 ----

    /**
     * 处理单个字符，根据当前状态执行状态转换。
     */
    private void processChar(char ch, List<Segment> segments) {
        switch (state) {
            case TEXT -> processTextState(ch, segments);
            case OPEN_TAG -> processOpenTagState(ch, segments);
            case CONTENT -> processContentState(ch, segments);
            case CLOSE_TAG -> processCloseTagState(ch, segments);
        }
    }

    /**
     * TEXT 状态：累积普通文本，遇到 '<' 时尝试进入 OPEN_TAG。
     */
    private void processTextState(char ch, List<Segment> segments) {
        if (ch == '<') {
            // 可能是开标签的开始，先刷出已有文本
            flushTextBuffer(segments);
            tagBuffer.append(ch);
            state = State.OPEN_TAG;
        } else {
            textBuffer.append(ch);
        }
    }

    /**
     * OPEN_TAG 状态：累积标签缓冲，检查是否匹配 {@code <a2ui>} 前缀。
     */
    private void processOpenTagState(char ch, List<Segment> segments) {
        tagBuffer.append(ch);
        String tag = tagBuffer.toString();

        if (tag.length() <= OPEN_TAG.length() && OPEN_TAG.startsWith(tag)) {
            // 仍然是 <a2ui> 的前缀，继续缓冲
            if (tag.length() == OPEN_TAG.length()) {
                // 完整匹配 <a2ui>，进入 CONTENT 状态
                tagBuffer.setLength(0);
                state = State.CONTENT;
            }
        } else {
            // 不匹配，回退缓冲内容为普通文本
            fallbackTagToText(segments);
        }
    }

    /**
     * CONTENT 状态：累积 JSON 内容，遇到 '<' 时尝试进入 CLOSE_TAG。
     */
    private void processContentState(char ch, List<Segment> segments) {
        if (ch == '<') {
            tagBuffer.append(ch);
            state = State.CLOSE_TAG;
        } else {
            contentBuffer.append(ch);
        }
    }

    /**
     * CLOSE_TAG 状态：累积标签缓冲，检查是否匹配 {@code </a2ui>} 前缀。
     */
    private void processCloseTagState(char ch, List<Segment> segments) {
        tagBuffer.append(ch);
        String tag = tagBuffer.toString();

        if (tag.length() <= CLOSE_TAG.length() && CLOSE_TAG.startsWith(tag)) {
            // 仍然是 </a2ui> 的前缀，继续缓冲
            if (tag.length() == CLOSE_TAG.length()) {
                // 完整匹配 </a2ui>，输出 A2uiSegment
                segments.add(new Segment.A2uiSegment(contentBuffer.toString()));
                contentBuffer.setLength(0);
                tagBuffer.setLength(0);
                state = State.TEXT;
            }
        } else {
            // 不匹配闭标签，将 tagBuffer 内容回退到 contentBuffer
            fallbackTagToContent(segments);
        }
    }

    /**
     * 将 tagBuffer 内容回退到 textBuffer（OPEN_TAG 不匹配时）。
     *
     * <p>回退后需要重新处理 tagBuffer 中可能包含的新 '<' 字符。
     */
    private void fallbackTagToText(List<Segment> segments) {
        String buffered = tagBuffer.toString();
        tagBuffer.setLength(0);
        state = State.TEXT;

        // 回退的内容中可能包含新的 '<'，需要逐字符重新处理
        // 但第一个字符一定是 '<'（进入 OPEN_TAG 时缓冲的），
        // 不匹配说明这个 '<' 不是 <a2ui> 的开始，直接作为文本
        // 从第二个字符开始检查是否有新的 '<'
        for (int i = 0; i < buffered.length(); i++) {
            char c = buffered.charAt(i);
            if (state == State.TEXT && c == '<' && i > 0) {
                // 遇到新的 '<'，可能是新的标签开始
                flushTextBuffer(segments);
                tagBuffer.append(c);
                state = State.OPEN_TAG;
            } else if (state == State.OPEN_TAG) {
                // 在重新处理过程中进入了 OPEN_TAG
                processOpenTagState(c, segments);
            } else {
                textBuffer.append(c);
            }
        }
    }

    /**
     * 将 tagBuffer 内容回退到 contentBuffer（CLOSE_TAG 不匹配时）。
     *
     * <p>回退后需要重新处理 tagBuffer 中可能包含的新 '<' 字符。
     */
    private void fallbackTagToContent(List<Segment> segments) {
        String buffered = tagBuffer.toString();
        tagBuffer.setLength(0);
        state = State.CONTENT;

        // 回退的内容中可能包含新的 '<'，需要逐字符重新处理
        // 第一个字符一定是 '<'（进入 CLOSE_TAG 时缓冲的），
        // 不匹配说明这个 '<' 不是 </a2ui> 的开始，直接作为内容
        for (int i = 0; i < buffered.length(); i++) {
            char c = buffered.charAt(i);
            if (state == State.CONTENT && c == '<' && i > 0) {
                // 遇到新的 '<'，可能是闭标签的开始
                tagBuffer.append(c);
                state = State.CLOSE_TAG;
            } else if (state == State.CLOSE_TAG) {
                // 在重新处理过程中进入了 CLOSE_TAG
                processCloseTagState(c, segments);
            } else {
                contentBuffer.append(c);
            }
        }
    }

    /**
     * 刷出 textBuffer 为 TextSegment（如果非空）。
     */
    private void flushTextBuffer(List<Segment> segments) {
        if (!textBuffer.isEmpty()) {
            segments.add(new Segment.TextSegment(textBuffer.toString()));
            textBuffer.setLength(0);
        }
    }
}
