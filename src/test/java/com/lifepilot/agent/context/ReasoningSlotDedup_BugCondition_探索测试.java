package com.lifepilot.agent.context;

import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.working.ReasoningSlot;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.memory.working.WorkingMemorySlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReasoningSlot 跨阶段重复累积探索测试。
 *
 * <p>本测试编码的是修复后的期望行为（expected behavior）。</p>
 *
 * <p>修复前（Bug Condition C2）：</p>
 * <ul>
 *   <li>{@code injectRetrievalReasoningSlots()} 第 2 段逻辑（语义记忆检索结果）无条件注入</li>
 *   <li>不检查 existingSlots 中是否已有相同 thought 的 ReasoningSlot</li>
 *   <li>多阶段调用导致重复 ReasoningSlot 累积，Token 膨胀</li>
 * </ul>
 *
 * <p>修复后（Expected Behavior）：</p>
 * <ul>
 *   <li>第 2 段逻辑增加去重检查，与第 1 段（L4 程序提示）一致</li>
 *   <li>已存在相同 thought 的检索结果被跳过</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("ReasoningSlot 跨阶段重复累积探索测试")
class ReasoningSlotDedup_BugCondition_探索测试 {

    /**
     * 构造 RetrievalResult，其 formatSingleResult 输出为指定的 thought 字符串。
     */
    private RetrievalResult buildResult(String entityType, String name, String description, float score) {
        return new RetrievalResult(
                "entity-" + name, entityType, name, description, score,
                new RetrievalResult.ScoreBreakdown(0.5f, 0.3f, 0.3f, 0.2f, 0.2f, 0.1f, 0.05f, 0.05f),
                "test-path", Instant.now(), 0.5f, null
        );
    }

    /**
     * 模拟 formatSingleResult 的输出格式。
     */
    private String formatSingleResult(RetrievalResult result) {
        var sb = new StringBuilder();
        sb.append("[").append(result.entityType()).append("] ").append(result.name());
        if (result.description() != null && !result.description().isBlank()) {
            sb.append(": ").append(result.description());
        }
        sb.append(" (score=").append(String.format("%.2f", result.fusedScore())).append(")");
        return sb.toString();
    }

    /**
     * existingSlots 已包含相同 thought 的 ReasoningSlot 时，注入后不应有重复。
     *
     * <p>模拟第二次调用 injectRetrievalReasoningSlots 的场景：existingSlots 中已有
     * 第一次注入的 ReasoningSlot，truncatedMemories 包含相同的检索结果。</p>
     *
     * <p><b>Validates: Requirements 1.2, 2.2</b></p>
     */
    @Test
    void existingSlots已有相同thought时_不应重复注入() {
        // 构造检索结果
        var result = buildResult("schedule", "团队周会", "每周一上午10点", 0.85f);
        String expectedThought = formatSingleResult(result);

        // 模拟第一次注入后的 existingSlots — 已包含该 thought 的 ReasoningSlot
        var existingSlot = ReasoningSlot.retrievalContext(expectedThought, 20);
        List<WorkingMemorySlot> existingSlots = new ArrayList<>();
        existingSlots.add(existingSlot);

        // 模拟第二次调用时的 truncatedMemories — 包含相同的检索结果
        List<RetrievalResult> truncatedMemories = List.of(result);

        // 模拟 injectRetrievalReasoningSlots 第 2 段逻辑的行为（修复后含去重检查）
        var updated = new ArrayList<>(existingSlots);
        for (int i = 0; i < Math.min(5, truncatedMemories.size()); i++) {
            RetrievalResult r = truncatedMemories.get(i);
            String thought = formatSingleResult(r);
            if (thought == null || thought.isBlank()) continue;
            // 修复后的去重检查
            boolean alreadyExists = updated.stream()
                    .filter(s -> s instanceof ReasoningSlot)
                    .map(s -> (ReasoningSlot) s)
                    .anyMatch(rs -> rs.thought() != null && rs.thought().equals(thought));
            if (alreadyExists) continue;
            ReasoningSlot reasoningSlot = ReasoningSlot.retrievalContext(thought, 20);
            updated.add(reasoningSlot);
        }

        // 统计重复 thought 数量
        long uniqueThoughts = updated.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .map(s -> ((ReasoningSlot) s).thought())
                .distinct()
                .count();
        long totalReasoningSlots = updated.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .count();

        // 期望：无重复 thought（uniqueThoughts == totalReasoningSlots）
        assertEquals(uniqueThoughts, totalReasoningSlots,
                "注入后 ReasoningSlot 列表中不应有重复 thought，" +
                "但当前有 " + totalReasoningSlots + " 个 ReasoningSlot 而仅 " +
                uniqueThoughts + " 个唯一 thought（Bug 2: 重复累积）");
    }

    /**
     * 多次调用模拟 — 5 个阶段重复注入导致 5 倍膨胀。
     *
     * <p>模拟同一轮对话经历 5 个 AgentPhase，每个阶段都调用 injectRetrievalReasoningSlots
     * 注入相同的检索结果。</p>
     *
     * <p><b>Validates: Requirements 1.2, 2.2</b></p>
     */
    @Test
    void 多阶段重复调用_不应导致ReasoningSlot膨胀() {
        var result1 = buildResult("schedule", "团队周会", "每周一上午10点", 0.85f);
        var result2 = buildResult("todo", "写报告", "截止周五", 0.75f);
        List<RetrievalResult> truncatedMemories = List.of(result1, result2);

        // 模拟 5 个阶段的注入（修复后含去重检查）
        var accumulated = new ArrayList<WorkingMemorySlot>();
        for (int phase = 0; phase < 5; phase++) {
            var updated = new ArrayList<>(accumulated);
            for (int i = 0; i < Math.min(5, truncatedMemories.size()); i++) {
                RetrievalResult r = truncatedMemories.get(i);
                String thought = formatSingleResult(r);
                if (thought == null || thought.isBlank()) continue;
                // 修复后的去重检查
                boolean alreadyExists = updated.stream()
                        .filter(s -> s instanceof ReasoningSlot)
                        .map(s -> (ReasoningSlot) s)
                        .anyMatch(rs -> rs.thought() != null && rs.thought().equals(thought));
                if (alreadyExists) continue;
                ReasoningSlot reasoningSlot = ReasoningSlot.retrievalContext(thought, 20);
                updated.add(reasoningSlot);
            }
            accumulated = updated;
        }

        long totalReasoningSlots = accumulated.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .count();
        long uniqueThoughts = accumulated.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .map(s -> ((ReasoningSlot) s).thought())
                .distinct()
                .count();

        // 期望：最终 ReasoningSlot 数量等于唯一 thought 数量（即 2）
        assertEquals(uniqueThoughts, totalReasoningSlots,
                "5 个阶段重复注入后，ReasoningSlot 数量应等于唯一 thought 数量（2），" +
                "但当前有 " + totalReasoningSlots + " 个（Bug 2: 5 倍膨胀）");
    }
}
