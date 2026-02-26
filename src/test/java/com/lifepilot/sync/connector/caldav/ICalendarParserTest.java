package com.lifepilot.sync.connector.caldav;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ICalendarParser RFC 5545 解析器单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class ICalendarParserTest {

    // ==================== parseComponent 测试 ====================

    @Nested
    class 解析组件 {

        @Test
        void 解析VEVENT组件() {
            var ical = """
                    BEGIN:VCALENDAR\r
                    VERSION:2.0\r
                    BEGIN:VEVENT\r
                    UID:uid-001\r
                    SUMMARY:团队周会\r
                    DESCRIPTION:讨论本周进展\r
                    DTSTART:20260301T090000Z\r
                    DTEND:20260301T100000Z\r
                    LOCATION:会议室A\r
                    DTSTAMP:20260228T120000Z\r
                    END:VEVENT\r
                    END:VCALENDAR\r
                    """;

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("uid-001", props.get("UID"));
            assertEquals("团队周会", props.get("SUMMARY"));
            assertEquals("讨论本周进展", props.get("DESCRIPTION"));
            assertEquals("20260301T090000Z", props.get("DTSTART"));
            assertEquals("20260301T100000Z", props.get("DTEND"));
            assertEquals("会议室A", props.get("LOCATION"));
            assertEquals("20260228T120000Z", props.get("DTSTAMP"));
            assertEquals("VEVENT", props.get("X-COMPONENT-TYPE"));
        }

        @Test
        void 解析VTODO组件() {
            var ical = """
                    BEGIN:VCALENDAR\r
                    VERSION:2.0\r
                    BEGIN:VTODO\r
                    UID:uid-002\r
                    SUMMARY:买牛奶\r
                    DESCRIPTION:去超市买两盒牛奶\r
                    DUE:20260305T180000Z\r
                    PRIORITY:1\r
                    STATUS:NEEDS-ACTION\r
                    DTSTAMP:20260228T120000Z\r
                    END:VTODO\r
                    END:VCALENDAR\r
                    """;

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("uid-002", props.get("UID"));
            assertEquals("买牛奶", props.get("SUMMARY"));
            assertEquals("去超市买两盒牛奶", props.get("DESCRIPTION"));
            assertEquals("20260305T180000Z", props.get("DUE"));
            assertEquals("1", props.get("PRIORITY"));
            assertEquals("NEEDS-ACTION", props.get("STATUS"));
            assertEquals("VTODO", props.get("X-COMPONENT-TYPE"));
        }

        @Test
        void 解析带参数的属性() {
            var ical = """
                    BEGIN:VCALENDAR\r
                    BEGIN:VEVENT\r
                    DTSTART;VALUE=DATE:20260301\r
                    SUMMARY:全天事件\r
                    END:VEVENT\r
                    END:VCALENDAR\r
                    """;

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("20260301", props.get("DTSTART"));
            assertEquals("全天事件", props.get("SUMMARY"));
        }

        @Test
        void 空文本返回空Map() {
            assertTrue(ICalendarParser.parseComponent("").isEmpty());
            assertTrue(ICalendarParser.parseComponent(null).isEmpty());
            assertTrue(ICalendarParser.parseComponent("   ").isEmpty());
        }

        @Test
        void 无组件返回空Map() {
            var ical = """
                    BEGIN:VCALENDAR\r
                    VERSION:2.0\r
                    END:VCALENDAR\r
                    """;
            assertTrue(ICalendarParser.parseComponent(ical).isEmpty());
        }

        @Test
        void 只提取第一个组件() {
            var ical = """
                    BEGIN:VCALENDAR\r
                    BEGIN:VEVENT\r
                    SUMMARY:第一个事件\r
                    END:VEVENT\r
                    BEGIN:VEVENT\r
                    SUMMARY:第二个事件\r
                    END:VEVENT\r
                    END:VCALENDAR\r
                    """;

            var props = ICalendarParser.parseComponent(ical);
            assertEquals("第一个事件", props.get("SUMMARY"));
        }
    }

    // ==================== formatVEvent / formatVTodo 测试 ====================

    @Nested
    class 格式化组件 {

        @Test
        void 格式化VEVENT() {
            var props = new LinkedHashMap<String, String>();
            props.put("UID", "uid-001");
            props.put("SUMMARY", "团队周会");
            props.put("DTSTART", "20260301T090000Z");
            props.put("DTEND", "20260301T100000Z");

            var result = ICalendarParser.formatVEvent(props);

            assertTrue(result.contains("BEGIN:VCALENDAR"));
            assertTrue(result.contains("BEGIN:VEVENT"));
            assertTrue(result.contains("UID:uid-001"));
            assertTrue(result.contains("SUMMARY:团队周会"));
            assertTrue(result.contains("DTSTART:20260301T090000Z"));
            assertTrue(result.contains("DTEND:20260301T100000Z"));
            assertTrue(result.contains("END:VEVENT"));
            assertTrue(result.contains("END:VCALENDAR"));
        }

        @Test
        void 格式化VTODO() {
            var props = new LinkedHashMap<String, String>();
            props.put("UID", "uid-002");
            props.put("SUMMARY", "买牛奶");
            props.put("DUE", "20260305T180000Z");
            props.put("PRIORITY", "1");
            props.put("STATUS", "NEEDS-ACTION");

            var result = ICalendarParser.formatVTodo(props);

            assertTrue(result.contains("BEGIN:VCALENDAR"));
            assertTrue(result.contains("BEGIN:VTODO"));
            assertTrue(result.contains("UID:uid-002"));
            assertTrue(result.contains("SUMMARY:买牛奶"));
            assertTrue(result.contains("DUE:20260305T180000Z"));
            assertTrue(result.contains("PRIORITY:1"));
            assertTrue(result.contains("STATUS:NEEDS-ACTION"));
            assertTrue(result.contains("END:VTODO"));
            assertTrue(result.contains("END:VCALENDAR"));
        }

        @Test
        void 格式化时跳过内部标记属性() {
            var props = new LinkedHashMap<String, String>();
            props.put("UID", "uid-001");
            props.put("X-COMPONENT-TYPE", "VEVENT");

            var result = ICalendarParser.formatVEvent(props);

            assertTrue(result.contains("UID:uid-001"));
            assertFalse(result.contains("X-COMPONENT-TYPE"));
        }
    }

    // ==================== unfold 测试 ====================

    @Nested
    class 多行折叠展开 {

        @Test
        void 展开CRLF加空格折叠() {
            var folded = "DESCRIPTION:这是一段很长的\r\n 描述文本";
            assertEquals("DESCRIPTION:这是一段很长的描述文本", ICalendarParser.unfold(folded));
        }

        @Test
        void 展开CRLF加制表符折叠() {
            var folded = "DESCRIPTION:这是一段很长的\r\n\t描述文本";
            assertEquals("DESCRIPTION:这是一段很长的描述文本", ICalendarParser.unfold(folded));
        }

        @Test
        void 展开LF加空格折叠() {
            var folded = "DESCRIPTION:这是一段很长的\n 描述文本";
            assertEquals("DESCRIPTION:这是一段很长的描述文本", ICalendarParser.unfold(folded));
        }

        @Test
        void 多次折叠展开() {
            var folded = "DESC:aaa\r\n bbb\r\n ccc";
            assertEquals("DESC:aaabbbccc", ICalendarParser.unfold(folded));
        }

        @Test
        void 无折叠文本不变() {
            var text = "SUMMARY:简短标题";
            assertEquals(text, ICalendarParser.unfold(text));
        }

        @Test
        void 空文本返回空字符串() {
            assertEquals("", ICalendarParser.unfold(""));
            assertEquals("", ICalendarParser.unfold(null));
        }
    }

    // ==================== fold 测试 ====================

    @Nested
    class 长行折叠 {

        @Test
        void 短行不折叠() {
            var line = "SUMMARY:短标题";
            assertEquals(line, ICalendarParser.fold(line));
        }

        @Test
        void 超过75字节的行被折叠() {
            // 构造一个超过 75 字节的行
            var longLine = "DESCRIPTION:" + "A".repeat(80);
            var folded = ICalendarParser.fold(longLine);

            // 折叠后应包含 CRLF + 空格
            assertTrue(folded.contains("\r\n "));

            // 展开后应恢复原始内容
            assertEquals(longLine, ICalendarParser.unfold(folded));
        }

        @Test
        void 折叠后每行不超过75字节() {
            var longLine = "DESCRIPTION:" + "X".repeat(200);
            var folded = ICalendarParser.fold(longLine);

            var lines = folded.split("\r\n");
            for (int i = 0; i < lines.length; i++) {
                var lineBytes = lines[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(lineBytes.length <= 75,
                        "第 %d 行超过 75 字节: %d 字节".formatted(i, lineBytes.length));
            }
        }

        @Test
        void 中文多字节字符不在中间截断() {
            // 中文字符每个占 3 字节 UTF-8
            var longLine = "SUMMARY:" + "中".repeat(30); // 8 + 90 = 98 字节
            var folded = ICalendarParser.fold(longLine);
            var unfolded = ICalendarParser.unfold(folded);

            assertEquals(longLine, unfolded);
        }

        @Test
        void 空行返回空字符串() {
            assertEquals("", ICalendarParser.fold(""));
            assertEquals("", ICalendarParser.fold(null));
        }
    }

    // ==================== escapeValue / unescapeValue 测试 ====================

    @Nested
    class 特殊字符转义 {

        @Test
        void 转义反斜杠() {
            assertEquals("path\\\\to\\\\file", ICalendarParser.escapeValue("path\\to\\file"));
        }

        @Test
        void 转义换行符() {
            assertEquals("第一行\\n第二行", ICalendarParser.escapeValue("第一行\n第二行"));
        }

        @Test
        void 转义逗号() {
            assertEquals("标签1\\,标签2", ICalendarParser.escapeValue("标签1,标签2"));
        }

        @Test
        void 转义分号() {
            assertEquals("值1\\;值2", ICalendarParser.escapeValue("值1;值2"));
        }

        @Test
        void 混合转义() {
            var original = "包含\\反斜杠,逗号;分号\n换行";
            var escaped = ICalendarParser.escapeValue(original);
            assertEquals("包含\\\\反斜杠\\,逗号\\;分号\\n换行", escaped);
        }

        @Test
        void 空值返回空字符串() {
            assertEquals("", ICalendarParser.escapeValue(""));
            assertEquals("", ICalendarParser.escapeValue(null));
        }

        @Test
        void 反转义反斜杠() {
            assertEquals("path\\to\\file", ICalendarParser.unescapeValue("path\\\\to\\\\file"));
        }

        @Test
        void 反转义换行符() {
            assertEquals("第一行\n第二行", ICalendarParser.unescapeValue("第一行\\n第二行"));
        }

        @Test
        void 反转义大写N换行符() {
            assertEquals("第一行\n第二行", ICalendarParser.unescapeValue("第一行\\N第二行"));
        }

        @Test
        void 反转义逗号() {
            assertEquals("标签1,标签2", ICalendarParser.unescapeValue("标签1\\,标签2"));
        }

        @Test
        void 反转义分号() {
            assertEquals("值1;值2", ICalendarParser.unescapeValue("值1\\;值2"));
        }

        @Test
        void 空值反转义返回空字符串() {
            assertEquals("", ICalendarParser.unescapeValue(""));
            assertEquals("", ICalendarParser.unescapeValue(null));
        }
    }

    // ==================== 往返测试 ====================

    @Nested
    class 往返转换 {

        @Test
        void VEVENT往返_格式化后解析恢复原始属性() {
            var original = new LinkedHashMap<String, String>();
            original.put("UID", "uid-roundtrip-001");
            original.put("SUMMARY", "往返测试事件");
            original.put("DESCRIPTION", "包含特殊字符：逗号,分号;换行\n反斜杠\\");
            original.put("DTSTART", "20260301T090000Z");
            original.put("DTEND", "20260301T100000Z");
            original.put("LOCATION", "会议室B");
            original.put("DTSTAMP", "20260228T120000Z");

            var icalText = ICalendarParser.formatVEvent(original);
            var parsed = ICalendarParser.parseComponent(icalText);

            assertEquals(original.get("UID"), parsed.get("UID"));
            assertEquals(original.get("SUMMARY"), parsed.get("SUMMARY"));
            assertEquals(original.get("DESCRIPTION"), parsed.get("DESCRIPTION"));
            assertEquals(original.get("DTSTART"), parsed.get("DTSTART"));
            assertEquals(original.get("DTEND"), parsed.get("DTEND"));
            assertEquals(original.get("LOCATION"), parsed.get("LOCATION"));
            assertEquals(original.get("DTSTAMP"), parsed.get("DTSTAMP"));
            assertEquals("VEVENT", parsed.get("X-COMPONENT-TYPE"));
        }

        @Test
        void VTODO往返_格式化后解析恢复原始属性() {
            var original = new LinkedHashMap<String, String>();
            original.put("UID", "uid-roundtrip-002");
            original.put("SUMMARY", "往返测试待办");
            original.put("DESCRIPTION", "详细描述");
            original.put("DUE", "20260310T180000Z");
            original.put("PRIORITY", "5");
            original.put("STATUS", "IN-PROCESS");
            original.put("DTSTAMP", "20260228T120000Z");

            var icalText = ICalendarParser.formatVTodo(original);
            var parsed = ICalendarParser.parseComponent(icalText);

            assertEquals(original.get("UID"), parsed.get("UID"));
            assertEquals(original.get("SUMMARY"), parsed.get("SUMMARY"));
            assertEquals(original.get("DESCRIPTION"), parsed.get("DESCRIPTION"));
            assertEquals(original.get("DUE"), parsed.get("DUE"));
            assertEquals(original.get("PRIORITY"), parsed.get("PRIORITY"));
            assertEquals(original.get("STATUS"), parsed.get("STATUS"));
            assertEquals("VTODO", parsed.get("X-COMPONENT-TYPE"));
        }

        @Test
        void 转义反转义往返() {
            var original = "包含\\反斜杠,逗号;分号\n换行";
            var escaped = ICalendarParser.escapeValue(original);
            var unescaped = ICalendarParser.unescapeValue(escaped);
            assertEquals(original, unescaped);
        }

        @Test
        void 折叠展开往返() {
            var longLine = "DESCRIPTION:" + "测试内容".repeat(20);
            var folded = ICalendarParser.fold(longLine);
            var unfolded = ICalendarParser.unfold(folded);
            assertEquals(longLine, unfolded);
        }
    }

    // ==================== 解析边界场景 ====================

    @Nested
    class 解析边界场景 {

        @Test
        void 解析包含折叠行的组件() {
            var ical = "BEGIN:VCALENDAR\r\n"
                    + "BEGIN:VEVENT\r\n"
                    + "SUMMARY:这是一个非常长的标题需要\r\n"
                    + " 被折叠到多行\r\n"
                    + "UID:uid-fold\r\n"
                    + "END:VEVENT\r\n"
                    + "END:VCALENDAR\r\n";

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("这是一个非常长的标题需要被折叠到多行", props.get("SUMMARY"));
            assertEquals("uid-fold", props.get("UID"));
        }

        @Test
        void 解析包含转义字符的属性值() {
            var ical = "BEGIN:VCALENDAR\r\n"
                    + "BEGIN:VEVENT\r\n"
                    + "SUMMARY:标题含\\,逗号\r\n"
                    + "DESCRIPTION:第一行\\n第二行\r\n"
                    + "UID:uid-escape\r\n"
                    + "END:VEVENT\r\n"
                    + "END:VCALENDAR\r\n";

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("标题含,逗号", props.get("SUMMARY"));
            assertEquals("第一行\n第二行", props.get("DESCRIPTION"));
        }

        @Test
        void 解析LF换行格式() {
            var ical = "BEGIN:VCALENDAR\n"
                    + "BEGIN:VTODO\n"
                    + "SUMMARY:LF格式待办\n"
                    + "UID:uid-lf\n"
                    + "END:VTODO\n"
                    + "END:VCALENDAR\n";

            var props = ICalendarParser.parseComponent(ical);

            assertEquals("LF格式待办", props.get("SUMMARY"));
            assertEquals("VTODO", props.get("X-COMPONENT-TYPE"));
        }
    }
}
