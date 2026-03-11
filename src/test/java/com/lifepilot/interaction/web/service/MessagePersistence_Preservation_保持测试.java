package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息持久化保持性测试。
 *
 * <p>验证修复前已有的正确行为在修复后不发生回归：</p>
 * <ul>
 *   <li>已有会话中 appendTurn() 正确写入消息</li>
 *   <li>历史消息按 created_at 升序返回</li>
 *   <li>删除会话时级联删除关联消息</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3</b></p>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("消息持久化保持性测试")
class MessagePersistence_Preservation_保持测试 {

    private JdbcTemplate jdbcTemplate;
    private ChatMessageRepository messageRepository;
    private ChatSessionRepository sessionRepository;
    private JdbcConversationHistoryStore historyStore;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 启用外键约束
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        // 创建 chat_sessions 表
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    id              TEXT PRIMARY KEY,
                    title           TEXT NOT NULL DEFAULT '新对话',
                    summary         TEXT,
                    message_count   INTEGER NOT NULL DEFAULT 0,
                    is_pinned       INTEGER NOT NULL DEFAULT 0,
                    archived        INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);

        // 创建 chat_messages 表
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id               TEXT PRIMARY KEY,
                    session_id       TEXT NOT NULL,
                    role             TEXT NOT NULL,
                    content          TEXT NOT NULL,
                    reasoning_summary TEXT,
                    trace_id         TEXT,
                    a2ui_components_json TEXT,
                    created_at       TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
                """);

        messageRepository = new ChatMessageRepository(jdbcTemplate, new ObjectMapper());
        sessionRepository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
        historyStore = new JdbcConversationHistoryStore(sessionRepository, messageRepository);
    }

    /**
     * 验证已有会话中 appendTurn() 正确写入用户消息和助手消息。
     *
     * <p>对于 activeSessionId 非空的已有会话，调用 appendTurn() 后，
     * 消息应正确写入 chat_messages 并可通过 sessionId 查询到。</p>
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    void appendTurn_已有会话中消息正确写入() {
        // 预创建会话（模拟已有会话，activeSessionId 非空）
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '测试会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        String userMessage = "帮我查一下明天的天气";
        String assistantMessage = "明天北京多云，气温 18-26°C。";
        String traceId = UUID.randomUUID().toString();

        // 调用 appendTurn 写入消息
        historyStore.appendTurn(sessionId, userMessage, assistantMessage, null, traceId);

        // 验证消息已写入 chat_messages 且可通过 sessionId 查询
        List<MessageInfo> messages = messageRepository.findMessageInfosBySessionId(sessionId);
        assertEquals(2, messages.size(), "appendTurn 应写入 2 条消息（user + assistant）");

        // 验证用户消息
        MessageInfo userMsg = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 user 角色消息"));
        assertEquals(userMessage, userMsg.content());

        // 验证助手消息
        MessageInfo assistantMsg = messages.stream()
                .filter(m -> "assistant".equals(m.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 assistant 角色消息"));
        assertEquals(assistantMessage, assistantMsg.content());
    }

    /**
     * 验证 findMessageInfosBySessionId 返回的消息按 created_at 升序排列。
     *
     * <p>插入多轮对话消息后，查询结果应严格按时间顺序返回，
     * 确保历史消息列表的展示顺序正确。</p>
     *
     * <p><b>Validates: Requirements 3.2</b></p>
     */
    @Test
    void findMessageInfos_消息按时间有序返回() {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '多轮对话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 插入多轮对话（appendTurn 内部 user 和 assistant 时间差 1ms）
        historyStore.appendTurn(sessionId, "第一轮用户消息", "第一轮助手回复", null, null);

        // 稍等确保时间戳不同
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        historyStore.appendTurn(sessionId, "第二轮用户消息", "第二轮助手回复", null, null);

        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        historyStore.appendTurn(sessionId, "第三轮用户消息", "第三轮助手回复", null, null);

        // 查询消息列表
        List<MessageInfo> messages = messageRepository.findMessageInfosBySessionId(sessionId);
        assertEquals(6, messages.size(), "3 轮对话应产生 6 条消息");

        // 验证时间严格递增
        for (int i = 1; i < messages.size(); i++) {
            Instant prev = messages.get(i - 1).timestamp();
            Instant curr = messages.get(i).timestamp();
            assertTrue(prev.isBefore(curr) || prev.equals(curr),
                    "消息 " + i + " 的时间戳应 >= 消息 " + (i - 1) + " 的时间戳，"
                            + "但 prev=" + prev + ", curr=" + curr);
        }

        // 验证消息内容顺序：user1, assistant1, user2, assistant2, user3, assistant3
        assertEquals("第一轮用户消息", messages.get(0).content());
        assertEquals("第一轮助手回复", messages.get(1).content());
        assertEquals("第二轮用户消息", messages.get(2).content());
        assertEquals("第二轮助手回复", messages.get(3).content());
        assertEquals("第三轮用户消息", messages.get(4).content());
        assertEquals("第三轮助手回复", messages.get(5).content());
    }

    @Test
    void findMessageInfos_保留A2ui组件和TraceId() {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, 'A2UI会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        String a2uiJson = """
                {"components":[
                  {"id":"card-1","type":"Card","properties":{"title":"待办面板"},"children":["btn-1"],"signal":null},
                  {"id":"btn-1","type":"Button","properties":{"label":"刷新"},"children":[],"signal":{"name":"panel.refresh","payload":{"section":"todos"}}}
                ]}
                """;

        historyStore.appendAssistantMessage(
                sessionId,
                "这是当前面板",
                "已生成结构化面板",
                "trace-a2ui-1",
                a2uiJson
        );

        List<MessageInfo> messages = messageRepository.findMessageInfosBySessionId(sessionId);

        assertEquals(1, messages.size());
        MessageInfo assistant = messages.getFirst();
        assertEquals("assistant", assistant.role());
        assertEquals("trace-a2ui-1", assistant.traceId());
        assertNotNull(assistant.a2uiComponents());
        assertEquals(2, assistant.a2uiComponents().size());
        assertEquals("card-1", assistant.a2uiComponents().getFirst().id());
        assertEquals("panel.refresh", assistant.a2uiComponents().get(1).signal().name());
    }

    /**
     * 验证删除会话时级联删除关联的 chat_messages 行。
     *
     * <p>chat_messages 表的 session_id 外键设置了 ON DELETE CASCADE，
     * 删除 chat_sessions 行时应自动清除所有关联消息。</p>
     *
     * <p><b>Validates: Requirements 3.3</b></p>
     */
    @Test
    void deleteSession_级联删除关联消息() {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '待删除会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 写入多条消息
        historyStore.appendTurn(sessionId, "用户消息1", "助手回复1", null, null);
        historyStore.appendTurn(sessionId, "用户消息2", "助手回复2", "推理摘要", null);

        // 确认消息已写入
        List<MessageInfo> beforeDelete = messageRepository.findMessageInfosBySessionId(sessionId);
        assertEquals(4, beforeDelete.size(), "删除前应有 4 条消息");

        // 删除会话
        sessionRepository.deleteById(sessionId);

        // 验证消息已级联删除
        List<MessageInfo> afterDelete = messageRepository.findMessageInfosBySessionId(sessionId);
        assertTrue(afterDelete.isEmpty(), "删除会话后，关联的 chat_messages 应被级联清除");

        // 额外验证：直接查 chat_messages 表确认无残留
        Integer remainingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
                Integer.class, sessionId);
        assertEquals(0, remainingCount, "chat_messages 中不应有残留记录");
    }
}
