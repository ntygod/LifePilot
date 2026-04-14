package com.lifepilot.agent.task.proactive.intent;

import com.lifepilot.memory.episodic.EpisodicMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IntentMemoryService 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class IntentMemoryService_单元测试 {

    IntentRepository intentRepository;
    IntentExtractor intentExtractor;
    EpisodicMemory episodicMemory;
    IntentMemoryService service;

    @BeforeEach
    void setUp() {
        intentRepository = mock(IntentRepository.class);
        intentExtractor = new IntentExtractor();
        episodicMemory = mock(EpisodicMemory.class);
        service = new IntentMemoryService(intentRepository, intentExtractor, episodicMemory);
    }

    @Test
    void 心跳过期清理() {
        var expired = new IntentRecord("i1", "u1", IntentType.GOAL, "已过期目标",
                null, null, IntentStatus.ACTIVE, 10,
                Instant.now().minusSeconds(100 * 86400), Instant.now().minusSeconds(86400),
                null, null, Instant.now().minusSeconds(100 * 86400));
        when(intentRepository.findExpired(any())).thenReturn(List.of(expired));

        service.expireStaleIntents();

        verify(intentRepository).updateStatus(eq("i1"), eq(IntentStatus.EXPIRED), any());
    }

    @Test
    void 查询活跃意图() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 0,
                Instant.now(), Instant.now().plusSeconds(90 * 86400), null, null, Instant.now());
        when(intentRepository.findActiveByUserId("u1")).thenReturn(List.of(intent));

        var result = service.getActiveIntents("u1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().goal()).isEqualTo("买耳机");
    }

    @Test
    void 标记意图完成() {
        service.fulfillIntent("i1");
        verify(intentRepository).updateStatus(eq("i1"), eq(IntentStatus.FULFILLED), any());
    }

    @Test
    void 递增检查计数() {
        service.incrementCheckCount("i1");
        verify(intentRepository).incrementCheckCount("i1");
    }

    @Test
    void 无EpisodicMemory时提取返回零() {
        var serviceNoMemory = new IntentMemoryService(intentRepository, intentExtractor, null);
        int count = serviceNoMemory.extractFromRecentConversations("u1");
        assertThat(count).isZero();
    }
}
