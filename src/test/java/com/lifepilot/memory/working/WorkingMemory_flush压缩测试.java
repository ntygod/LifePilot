package com.lifepilot.memory.working;

import com.lifepilot.memory.compression.CompressionService;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkingMemory flush 自动压缩单元测试。
 *
 * <p>验证 flush() 在不同场景下的自动压缩触发行为：
 * <ul>
 *   <li>超阈值时触发压缩</li>
 *   <li>未超阈值时不触发压缩</li>
 *   <li>CompressionService 为 null 时正常 flush</li>
 *   <li>CompressionService 抛异常时 flush 不受影响</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@ExtendWith(MockitoExtension.class)
class WorkingMemory_flush压缩测试 {

    @Mock private EpisodicMemory episodicMemory;
    @Mock private SlotEvictionPolicy slotEvictionPolicy;
    @Mock private MemoryEventRecorder memoryEventRecorder;
    @Mock private WorkingMemoryWal wal;
    @Mock private CompressionService compressionService;

    private MemoryProperties properties;
    private TokenBudgetAllocator tokenBudgetAllocator;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        // 默认压缩阈值 4000 Token
        properties.setCompressionThresholdTokens(4000);
        // 设置足够大的 Token 预算，避免淘汰干扰测试
        properties.setWorkingMemoryTokenBudget(100_000);

        // 使用真实的 TokenBudgetAllocator（基于 properties 配置）
        tokenBudgetAllocator = new TokenBudgetAllocator(properties);
    }

    /** 创建带压缩服务的 WorkingMemory 实例。 */
    private WorkingMemory createWithCompression() {
        return new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);
    }

    /** 创建不带压缩服务的 WorkingMemory 实例。 */
    private WorkingMemory createWithoutCompression() {
        return new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, null);
    }

    @Test
    void flush后触发压缩_超阈值场景() {
        // 准备：每条消息 1000 Token，5 条 = 5000 Token > 阈值 4000
        when(compressionService.shouldCompress(5000)).thenReturn(true);

        var wm = createWithCompression();
        String sessionId = "session-compress";

        // 追加 5 条高 Token 消息
        for (int i = 0; i < 5; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "消息内容" + i, 1000, 0.8f, false, null, Instant.now()));
        }

        ConversationRecord record = wm.flush(sessionId, "测试目标");

        // 验证 flush 正常返回
        assertNotNull(record);
        assertEquals(5, record.messages().size());

        // 验证 episodicMemory.save 被调用
        verify(episodicMemory).save(any(ConversationRecord.class));

        // 验证 compressionService.shouldCompress 被调用
        verify(compressionService).shouldCompress(5000);

        // 验证 compressWithSlidingWindow 被调用
        verify(compressionService).compressWithSlidingWindow(
                eq(record.id()), anyList(), eq(CompressionLevel.SUMMARY));
    }

    @Test
    void flush后不触发压缩_未超阈值场景() {
        // 准备：每条消息 100 Token，3 条 = 300 Token < 阈值 4000
        when(compressionService.shouldCompress(300)).thenReturn(false);

        var wm = createWithCompression();
        String sessionId = "session-no-compress";

        for (int i = 0; i < 3; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "短消息" + i, 100, 0.8f, false, null, Instant.now()));
        }

        ConversationRecord record = wm.flush(sessionId, "测试目标");

        assertNotNull(record);
        assertEquals(3, record.messages().size());

        // 验证 shouldCompress 被调用但返回 false
        verify(compressionService).shouldCompress(300);

        // 验证 compressWithSlidingWindow 未被调用
        verify(compressionService, never()).compressWithSlidingWindow(
                anyString(), anyList(), any(CompressionLevel.class));
    }

    @Test
    void CompressionService为null时正常flush() {
        var wm = createWithoutCompression();
        String sessionId = "session-null-cs";

        for (int i = 0; i < 3; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "消息" + i, 2000, 0.8f, false, null, Instant.now()));
        }

        ConversationRecord record = wm.flush(sessionId, "测试目标");

        // flush 正常完成
        assertNotNull(record);
        assertEquals(3, record.messages().size());

        // 验证 episodicMemory.save 被调用
        verify(episodicMemory).save(any(ConversationRecord.class));

        // CompressionService 为 null，不应有任何交互
        verifyNoInteractions(compressionService);
    }

    @Test
    void CompressionService抛异常时flush不受影响() {
        when(compressionService.shouldCompress(5000)).thenReturn(true);
        doThrow(new RuntimeException("压缩服务异常"))
                .when(compressionService).compressWithSlidingWindow(
                        anyString(), anyList(), any(CompressionLevel.class));

        var wm = createWithCompression();
        String sessionId = "session-exception";

        for (int i = 0; i < 5; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "消息内容" + i, 1000, 0.8f, false, null, Instant.now()));
        }

        // flush 不应抛异常
        ConversationRecord record = wm.flush(sessionId, "测试目标");

        // flush 正常返回
        assertNotNull(record);
        assertEquals(5, record.messages().size());

        // 验证 episodicMemory.save 被调用
        verify(episodicMemory).save(any(ConversationRecord.class));

        // 验证压缩被尝试调用（虽然抛异常）
        verify(compressionService).compressWithSlidingWindow(
                eq(record.id()), anyList(), eq(CompressionLevel.SUMMARY));

        // 验证会话已清除（flush 正常完成）
        assertTrue(wm.getContext(sessionId).isEmpty());
    }
}
