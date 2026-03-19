package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息持久化时序缺陷探索测试。
 *
 * <p>本测试编码的是修复后的期望行为（expected behavior）。</p>
 *
 * <p>修复前（Bug Condition C2）：</p>
 * <ul>
 *   <li>AgentLoop.runStreaming() 生成 tempTurnId 作为 done 事件的 messageId</li>
 *   <li>asyncPostProcess() 在 Virtual Thread 中异步调用 appendTurn()</li>
 *   <li>ChatMessageRepository.insert() 内部生成新的 UUID 作为 chat_messages 主键</li>
 *   <li>结果：done 事件中的 messageId 与 chat_messages 中的 ID 不一致</li>
 * </ul>
 *
 * <p>修复后（Expected Behavior）：</p>
 * <ul>
 *   <li>AgentLoop 调用 appendUserMessage() / appendAssistantMessage() 同步写入</li>
 *   <li>方法返回后端生成的 messageId，done 事件直接使用该 ID</li>
 *   <li>结果：前后端 messageId 一致，反馈查询可靠</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("消息持久化时序缺陷探索测试")
class MessagePersistence_BugCondition_探索测试 {

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

        // 创建 chat_sessions 表（V22 + V24）
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

        // 创建 chat_messages 表（V31）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id               TEXT PRIMARY KEY,
                    session_id       TEXT NOT NULL,
                    role             TEXT NOT NULL,
                    content          TEXT NOT NULL,
                    reasoning_summary TEXT,
                    trace_id         TEXT,
                    a2ui_components_json TEXT,
                    react_steps_json TEXT,
                    created_at       TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
                """);

        messageRepository = new ChatMessageRepository(jdbcTemplate, new ObjectMapper());
        sessionRepository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
        historyStore = new JdbcConversationHistoryStore(sessionRepository, messageRepository);
    }

    /**
     * 验证修复后行为：appendAssistantMessage() 返回的 messageId 在 chat_messages 中存在。
     *
     * <p>修复前：AgentLoop 生成 tempTurnId，appendTurn() 内部生成不同 UUID，两者不一致。</p>
     * <p>修复后：appendAssistantMessage() 返回后端生成的 messageId，AgentLoop 直接使用。</p>
     *
     * <p><b>Validates: Requirements 1.5, 2.6</b></p>
     */
    @Test
    void messageId_done事件与数据库一致() {
        String sessionId = UUID.randomUUID().toString();
        String userMessage = "今天天气怎么样？";
        String assistantMessage = "今天北京晴，气温 25°C。";
        String traceId = UUID.randomUUID().toString();

        // 预创建会话
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '测试会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 模拟修复后的 AgentLoop 流程：
        // 1. 同步调用 appendUserMessage() 写入用户消息
        String userMessageId = historyStore.appendUserMessage(sessionId, userMessage, traceId);
        // 2. 同步调用 appendAssistantMessage() 写入助手消息，获取 messageId
        String assistantMessageId = historyStore.appendAssistantMessage(sessionId, assistantMessage, null, traceId, null, null);

        // 验证：返回的 messageId 在 chat_messages 中能找到
        assertTrue(messageRepository.messageExists(assistantMessageId),
                "appendAssistantMessage() 返回的 messageId 在 chat_messages 中应存在");
        assertTrue(messageRepository.messageExists(userMessageId),
                "appendUserMessage() 返回的 messageId 在 chat_messages 中应存在");
    }

    /**
     * 验证修复后行为：消息同步写入，done 事件发送时消息已持久化。
     *
     * <p>修复前：asyncPostProcess() 在 Virtual Thread 中异步写入，done 事件发送时消息可能未持久化。</p>
     * <p>修复后：appendUserMessage/appendAssistantMessage 同步写入，方法返回时消息行已存在。</p>
     *
     * <p><b>Validates: Requirements 1.3, 1.4, 2.3, 2.4</b></p>
     */
    @Test
    void 消息同步写入_方法返回时已持久化() {
        String sessionId = UUID.randomUUID().toString();
        String userMessage = "帮我安排明天的日程";
        String assistantMessage = "好的，我已为你安排了明天的日程。";
        String traceId = UUID.randomUUID().toString();

        // 预创建会话
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '测试会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 同步写入用户消息
        historyStore.appendUserMessage(sessionId, userMessage, traceId);

        // 立即检查：用户消息已持久化
        Integer countAfterUser = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
                Integer.class, sessionId);
        assertEquals(1, countAfterUser, "appendUserMessage() 返回后，chat_messages 中应有 1 条用户消息");

        // 同步写入助手消息
        historyStore.appendAssistantMessage(sessionId, assistantMessage, null, traceId, null, null);

        // 立即检查：两条消息都已持久化
        Integer countAfterAssistant = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
                Integer.class, sessionId);
        assertEquals(2, countAfterAssistant, "appendAssistantMessage() 返回后，chat_messages 中应有 2 条消息");
    }

    /**
     * 验证修复后行为：反馈请求携带的 messageId 能在数据库中找到。
     *
     * <p>修复前：tempTurnId 与 chat_messages 中的 ID 不一致，getSessionIdByMessageId 返回 null。</p>
     * <p>修复后：appendAssistantMessage() 返回的 messageId 即为 chat_messages 主键，查询可靠。</p>
     *
     * <p><b>Validates: Requirements 1.4, 1.5, 2.5</b></p>
     */
    @Test
    void feedback_messageId能在数据库中找到() {
        String sessionId = UUID.randomUUID().toString();
        String userMessage = "推荐一本好书";
        String assistantMessage = "推荐《深入理解计算机系统》。";
        String traceId = UUID.randomUUID().toString();

        // 预创建会话
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '测试会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 模拟修复后的 AgentLoop 流程
        historyStore.appendUserMessage(sessionId, userMessage, traceId);
        String assistantMessageId = historyStore.appendAssistantMessage(sessionId, assistantMessage, null, traceId, null, null);

        // 模拟反馈提交：用 done 事件中的 messageId 查询 sessionId
        String foundSessionId = messageRepository.findSessionIdByMessageId(assistantMessageId);

        assertNotNull(foundSessionId,
                "反馈请求携带的 messageId 应能在 chat_messages 中找到对应的 sessionId");
        assertEquals(sessionId, foundSessionId,
                "反馈请求查到的 sessionId 应与原始会话一致");
    }
}
