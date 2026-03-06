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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息持久化时序缺陷探索测试。
 *
 * <p>本测试编码的是修复后的期望行为（expected behavior），因此在未修复代码上运行时
 * 预期会失败，失败即确认 bug 存在。</p>
 *
 * <p>Bug Condition C2: 消息异步写入竞态 + ID 不一致</p>
 * <ul>
 *   <li>AgentLoop.runStreaming() 生成 tempTurnId 作为 done 事件的 messageId</li>
 *   <li>asyncPostProcess() 在 Virtual Thread 中异步调用 appendTurn()</li>
 *   <li>ChatMessageRepository.insert() 内部生成新的 UUID 作为 chat_messages 主键</li>
 *   <li>结果：done 事件中的 messageId 与 chat_messages 中的 ID 不一致</li>
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
                    created_at       TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
                """);

        messageRepository = new ChatMessageRepository(jdbcTemplate);
        sessionRepository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
        historyStore = new JdbcConversationHistoryStore(sessionRepository, messageRepository);
    }

    /**
     * 验证 Bug Condition C2b: messageId 不一致。
     *
     * <p>模拟 AgentLoop.runStreaming() 的行为：</p>
     * <ol>
     *   <li>生成 tempTurnId = UUID.randomUUID()（模拟 AgentLoop Line 143）</li>
     *   <li>调用 appendTurn() 写入消息（模拟 asyncPostProcess 中的持久化）</li>
     *   <li>检查 tempTurnId 是否能在 chat_messages 中找到对应行</li>
     * </ol>
     *
     * <p>期望行为（修复后）：done 事件中的 messageId 与 chat_messages 中助手消息主键一致。</p>
     * <p>当前行为（未修复）：tempTurnId 与 chat_messages 中的 ID 不一致，断言失败。</p>
     *
     * <p><b>Validates: Requirements 1.5, 2.6</b></p>
     */
    @Test
    void messageId_done事件与数据库不一致() {
        String sessionId = UUID.randomUUID().toString();
        String userMessage = "今天天气怎么样？";
        String assistantMessage = "今天北京晴，气温 25°C。";
        String traceId = UUID.randomUUID().toString();

        // 预创建会话（模拟正常流程中会话已存在）
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (id, title, message_count, is_pinned, archived, created_at, updated_at)
                VALUES (?, '测试会话', 0, 0, 0, ?, ?)
                """, sessionId, now, now);

        // 模拟 AgentLoop.runStreaming() Line 143: 生成 tempTurnId
        String tempTurnId = UUID.randomUUID().toString();

        // 模拟 asyncPostProcess() 中的 conversationHistoryStore.appendTurn()
        // 当前实现中，appendTurn() 内部通过 ChatMessageRepository.insert() 生成新的 UUID
        historyStore.appendTurn(sessionId, userMessage, assistantMessage, null, traceId);

        // 期望行为：done 事件中的 messageId（tempTurnId）在 chat_messages 中能找到
        // 当前行为：tempTurnId 与 chat_messages 中的 ID 不一致，因为 insert() 生成了不同的 UUID
        boolean tempTurnIdExistsInDb = messageRepository.messageExists(tempTurnId);

        // 此断言在未修复代码上会失败 — 确认 bug 存在
        assertTrue(tempTurnIdExistsInDb,
                "done 事件中的 messageId（tempTurnId=" + tempTurnId
                        + "）在 chat_messages 中应该能找到对应行，"
                        + "但当前 ChatMessageRepository.insert() 生成了不同的 UUID");
    }

    /**
     * 验证 Bug Condition C2a: 异步写入竞态。
     *
     * <p>模拟 AgentLoop.runStreaming() 中 asyncPostProcess() 的异步行为：</p>
     * <ol>
     *   <li>在 Virtual Thread 中调用 appendTurn()（模拟 asyncPostProcess）</li>
     *   <li>在 asyncPostProcess 启动后立即检查 chat_messages（模拟 done 事件发送时机）</li>
     *   <li>验证消息是否已持久化</li>
     * </ol>
     *
     * <p>期望行为（修复后）：done 事件发送前消息已同步写入，检查时消息行必定存在。</p>
     * <p>当前行为（未修复）：asyncPostProcess 在 Virtual Thread 中异步执行，
     * done 事件发送时消息可能尚未写入。</p>
     *
     * <p><b>Validates: Requirements 1.3, 1.4, 2.3, 2.4</b></p>
     */
    @Test
    void asyncPostProcess_done事件发送时消息可能未持久化() {
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

        // 模拟 asyncPostProcess() 的行为：在 Virtual Thread 中异步写入
        // 这复现了 AgentLoop.asyncPostProcess() 的实际模式
        CountDownLatch asyncStarted = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            asyncStarted.countDown();
            // 模拟 asyncPostProcess 中的 conversationHistoryStore.appendTurn()
            historyStore.appendTurn(sessionId, userMessage, assistantMessage, null, traceId);
        });

        // 模拟 done 事件发送时机：asyncPostProcess 启动后立即检查
        // 在真实场景中，done 事件在 finally 块中同步发送，此时 Virtual Thread 可能尚未执行完毕
        try {
            asyncStarted.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 立即检查消息是否已持久化（不等待异步完成）
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
                Integer.class, sessionId);

        // 期望行为（修复后）：消息应该在 done 事件发送前已同步写入，count >= 2（user + assistant）
        // 当前行为（未修复）：异步写入可能尚未完成，count 可能为 0
        // 注意：由于测试环境中 Virtual Thread 调度不确定，此测试可能偶尔通过
        // 但核心问题是：当前架构不保证 done 事件发送时消息已持久化
        assertTrue(messageCount != null && messageCount >= 2,
                "done 事件发送时，chat_messages 中应至少有 2 条消息（user + assistant），"
                        + "但当前 asyncPostProcess 异步写入可能尚未完成，实际 count=" + messageCount);
    }

    /**
     * 验证 Bug Condition C2b: 反馈请求携带的 messageId 在数据库中查不到。
     *
     * <p>模拟完整的"发送消息 → done 事件 → 提交反馈"流程：</p>
     * <ol>
     *   <li>生成 tempTurnId（模拟 AgentLoop）</li>
     *   <li>调用 appendTurn() 写入消息（模拟 asyncPostProcess）</li>
     *   <li>用 tempTurnId 查询 sessionId（模拟反馈提交时的 getSessionIdByMessageId）</li>
     * </ol>
     *
     * <p>期望行为（修复后）：反馈请求携带的 messageId 能在 chat_messages 中找到对应行。</p>
     * <p>当前行为（未修复）：tempTurnId 与 chat_messages 中的 ID 不一致，查询返回 null。</p>
     *
     * <p><b>Validates: Requirements 1.4, 1.5, 2.5</b></p>
     */
    @Test
    void feedback_messageId在数据库中查不到() {
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

        // 模拟 AgentLoop: 生成 tempTurnId 作为 done 事件的 messageId
        String tempTurnId = UUID.randomUUID().toString();

        // 模拟 asyncPostProcess: appendTurn() 写入消息（内部生成不同的 UUID）
        historyStore.appendTurn(sessionId, userMessage, assistantMessage, null, traceId);

        // 模拟反馈提交：用 done 事件中的 messageId（tempTurnId）查询 sessionId
        // 这是 MessageFeedbackRepository.getSessionIdByMessageId() 的逻辑
        String foundSessionId = messageRepository.findSessionIdByMessageId(tempTurnId);

        // 期望行为（修复后）：tempTurnId 对应的消息行存在，能查到 sessionId
        // 当前行为（未修复）：tempTurnId 在 chat_messages 中不存在，返回 null
        assertNotNull(foundSessionId,
                "反馈请求携带的 messageId（tempTurnId=" + tempTurnId
                        + "）应能在 chat_messages 中找到对应的 sessionId，"
                        + "但当前 ChatMessageRepository.insert() 生成了不同的 UUID，导致查询返回 null");
        assertEquals(sessionId, foundSessionId,
                "反馈请求查到的 sessionId 应与原始会话一致");
    }
}
