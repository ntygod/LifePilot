package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.semantic.ConflictResolutionRepository;
import com.lifepilot.memory.semantic.ConflictResolutionRepository.QueueItem;
import com.lifepilot.memory.semantic.ConflictResolutionService;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConflictResolutionRetry} 单元测试 —— 全部依赖用 Mockito 模拟，聚焦验证
 * 重试 Cron 的业务编排：拉取 → 回捞实体 → 过滤空候选 → 触发 resolveAsync。
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("ConflictResolutionRetry 单元测试")
class ConflictResolutionRetry_单元测试 {

    private ConflictResolutionRepository queueRepository;
    private ConflictResolutionService resolutionService;
    private SemanticMemory semanticMemory;
    private ConflictResolutionRetry retry;

    @BeforeEach
    void 初始化() {
        queueRepository = mock(ConflictResolutionRepository.class);
        resolutionService = mock(ConflictResolutionService.class);
        semanticMemory = mock(SemanticMemory.class);
        retry = new ConflictResolutionRetry(queueRepository, resolutionService, semanticMemory);
    }

    @Test
    void 失败任务应重新调resolveAsync_候选齐全() {
        var newEntity = 构造实体("new-1");
        var candA = 构造实体("cand-A");
        var candB = 构造实体("cand-B");
        var item = new QueueItem("q-1", "new-1", List.of("cand-A", "cand-B"));

        when(queueRepository.findFailedRetriable(3)).thenReturn(List.of(item));
        when(semanticMemory.findById("new-1")).thenReturn(Optional.of(newEntity));
        when(semanticMemory.findById("cand-A")).thenReturn(Optional.of(candA));
        when(semanticMemory.findById("cand-B")).thenReturn(Optional.of(candB));

        retry.retryNow();

        verify(resolutionService).resolveAsync(eq(newEntity), anyList());
    }

    @Test
    void 新实体已不存在应跳过该条() {
        var item = new QueueItem("q-2", "ghost-new", List.of("cand-X"));
        when(queueRepository.findFailedRetriable(3)).thenReturn(List.of(item));
        when(semanticMemory.findById("ghost-new")).thenReturn(Optional.empty());

        retry.retryNow();

        // 候选的 findById 根本不该被调（新实体已空直接短路）
        verify(semanticMemory, never()).findById("cand-X");
        verify(resolutionService, never()).resolveAsync(any(), anyList());
    }

    @Test
    void 所有候选均不存在应跳过不调resolveAsync() {
        var newEntity = 构造实体("new-3");
        var item = new QueueItem("q-3", "new-3", List.of("ghost-1", "ghost-2"));

        when(queueRepository.findFailedRetriable(3)).thenReturn(List.of(item));
        when(semanticMemory.findById("new-3")).thenReturn(Optional.of(newEntity));
        when(semanticMemory.findById("ghost-1")).thenReturn(Optional.empty());
        when(semanticMemory.findById("ghost-2")).thenReturn(Optional.empty());

        retry.retryNow();

        verify(resolutionService, never()).resolveAsync(any(), anyList());
    }

    @Test
    void 空队列不抛异常且不调resolveAsync() {
        when(queueRepository.findFailedRetriable(3)).thenReturn(List.of());

        // 不抛异常即算通过
        retry.retryNow();

        verify(resolutionService, never()).resolveAsync(any(), anyList());
        verify(semanticMemory, never()).findById(any());
    }

    @Test
    void 单条异常不中断整批_仍处理后续() {
        var newGood = 构造实体("good-new");
        var candGood = 构造实体("good-cand");
        var newBad = 构造实体("bad-new");
        var candBad = 构造实体("bad-cand");

        var badItem = new QueueItem("q-bad", "bad-new", List.of("bad-cand"));
        var goodItem = new QueueItem("q-good", "good-new", List.of("good-cand"));

        when(queueRepository.findFailedRetriable(3)).thenReturn(List.of(badItem, goodItem));
        when(semanticMemory.findById("bad-new")).thenReturn(Optional.of(newBad));
        when(semanticMemory.findById("bad-cand")).thenReturn(Optional.of(candBad));
        when(semanticMemory.findById("good-new")).thenReturn(Optional.of(newGood));
        when(semanticMemory.findById("good-cand")).thenReturn(Optional.of(candGood));

        // bad 项调 resolveAsync 时抛异常
        doThrow(new RuntimeException("模拟 LLM 提交失败"))
                .when(resolutionService).resolveAsync(eq(newBad), anyList());

        // 不抛异常即代表整批未中断
        retry.retryNow();

        // good 项仍然被调用
        verify(resolutionService, times(1)).resolveAsync(eq(newBad), anyList());
        verify(resolutionService, times(1)).resolveAsync(eq(newGood), anyList());
    }

    // ---------- 测试夹具 ----------

    private TemporalEntity 构造实体(String id) {
        Instant now = Instant.parse("2026-04-23T04:00:00Z");
        return new TemporalEntity(
                id, EntityType.PERSON, id, "描述-" + id,
                Map.of(), 1, true, now, null, null,
                0.9f, 0.5f, 0, null, now, now,
                LifecycleState.ACTIVE, null, null,
                com.lifepilot.memory.lifecycle.Temporality.PERSISTENT,
                null, false, List.of()
        );
    }
}
