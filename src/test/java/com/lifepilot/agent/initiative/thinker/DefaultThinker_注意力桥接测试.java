package com.lifepilot.agent.initiative.thinker;

import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionItem;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultThinker 记忆注意力桥接测试 —— 验证空闲思考从注意力信号生成想法（不表达）。
 *
 * @author zsg
 * @since 2026-06-07
 */
class DefaultThinker_注意力桥接测试 {

    private AttentionItem item(AttentionKind kind, String id, String name, float score) {
        return new AttentionItem(id, name, "GOAL", kind, score, "原因-" + name, Instant.now(), null, null);
    }

    @Test
    void 注意力信号映射为对应想法类型() {
        var attention = mock(MemoryAttentionService.class);
        when(attention.computeAttention(any(), anyInt())).thenReturn(List.of(
                item(AttentionKind.DUE_SOON, "g1", "述职报告", 0.8f),
                item(AttentionKind.NEGLECTED, "g2", "学小提琴", 0.6f),
                item(AttentionKind.CONNECTION, "g3", "网易", 0.7f)));
        var thinker = new DefaultThinker(attention);

        var thoughts = thinker.idleThink();

        assertThat(thoughts).extracting(t -> t.kind())
                .containsExactlyInAnyOrder(ThoughtKind.REMINDER, ThoughtKind.FOLLOW_UP, ThoughtKind.INSIGHT);
        assertThat(thoughts).allSatisfy(t -> assertThat(t.summary()).startsWith("原因-"));
    }

    @Test
    void DUE_SOON高分应为就绪REMINDER() {
        var attention = mock(MemoryAttentionService.class);
        when(attention.computeAttention(any(), anyInt())).thenReturn(List.of(
                item(AttentionKind.DUE_SOON, "g1", "述职报告", 0.9f)));
        var thinker = new DefaultThinker(attention);

        var thoughts = thinker.idleThink();

        assertThat(thoughts).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(ThoughtKind.REMINDER);
            assertThat(t.intentKey()).isEqualTo("reminder:due_soon:g1");
            assertThat(t.isReady()).isTrue();
        });
    }

    @Test
    void EVOLVING不产生想法() {
        var attention = mock(MemoryAttentionService.class);
        when(attention.computeAttention(any(), anyInt())).thenReturn(List.of(
                item(AttentionKind.EVOLVING, "g1", "演进中目标", 0.5f)));
        var thinker = new DefaultThinker(attention);

        assertThat(thinker.idleThink()).isEmpty();
    }

    @Test
    void 注意力计算失败时返回空列表() {
        var attention = mock(MemoryAttentionService.class);
        when(attention.computeAttention(any(), anyInt())).thenThrow(new IllegalStateException("索引暂不可用"));
        var thinker = new DefaultThinker(attention);

        assertThat(thinker.idleThink()).isEmpty();
    }
}
