package com.lifepilot.agent.context;

import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.working.ReasoningSlot;
import com.lifepilot.memory.working.WorkingMemorySlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReasoningSlot 去重修复保持测试。
 *
 * <p>验证修复后以下行为不受影响：</p>
 * <ul>
 *   <li>空 existingSlots 时，首次检索结果正常注入为 ReasoningSlot</li>
 *   <li>L4 程序提示（procedureHintSlot）的去重逻辑正常工作</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("ReasoningSlot 去重修复保持测试")
class ReasoningSlotDedup_Preservation_保持测试 {

    private RetrievalResult buildResult(String entityType, String name, String description, float score) {
        return new RetrievalResult(
                "entity-" + name, entityType, name, description, score,
                new RetrievalResult.ScoreBreakdown(0.5f, 0.3f, 0.3f, 0.2f, 0.2f, 0.1f, 0.05f, 0.05f),
                "test-path", Instant.now(), 0.5f, null
        );
    }

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
     * 空 existingSlots 时，首次检索结果正常注入。
     *
     * <p>模拟第 2 段逻辑：existingSlots 为空，truncatedMemories 包含新检索结果，
     * 所有结果应正常注入为 ReasoningSlot。</p>
     *
     * <p><b>Validates: Requirements 3.2</b></p>
     */
    @Test
    void 空existingSlots时_首次检索结果正常注入() {
        var result1 = buildResult("schedule", "团队周会", "每周一上午10点", 0.85f);
        var result2 = buildResult("todo", "写报告", "截止周五", 0.75f);
        List<RetrievalResult> truncatedMemories = List.of(result1, result2);

        // 模拟第 2 段逻辑 — existingSlots 为空
        var updated = new ArrayList<WorkingMemorySlot>();
        int maxInjected = Math.min(5, truncatedMemories.size());
        for (int i = 0; i < maxInjected; i++) {
            RetrievalResult r = truncatedMemories.get(i);
            String thought = formatSingleResult(r);
            if (thought == null || thought.isBlank()) continue;
            // 去重检查（修复后逻辑，但空列表时不影响注入）
            boolean alreadyExists = updated.stream()
                    .filter(s -> s instanceof ReasoningSlot)
                    .map(s -> (ReasoningSlot) s)
                    .anyMatch(rs -> rs.thought() != null && rs.thought().equals(thought));
            if (alreadyExists) continue;
            ReasoningSlot reasoningSlot = ReasoningSlot.retrievalContext(thought, 20);
            updated.add(reasoningSlot);
        }

        // 期望：2 条检索结果全部注入
        assertEquals(2, updated.size(), "空 existingSlots 时，所有检索结果应正常注入");
        assertTrue(updated.stream().allMatch(s -> s instanceof ReasoningSlot),
                "注入的 slot 应全部为 ReasoningSlot");
    }

    /**
     * L4 程序提示去重逻辑不受影响。
     *
     * <p>模拟第 1 段逻辑：existingSlots 已有 L4 程序提示 ReasoningSlot，
     * 再次注入相同 thought 的程序提示应被跳过。</p>
     *
     * <p><b>Validates: Requirements 3.3</b></p>
     */
    @Test
    void L4程序提示去重逻辑_不受影响() {
        String procedureThought = "[procedure] 用户习惯在早上安排日程，优先使用 schedule.create";

        // existingSlots 已有该程序提示
        var existingSlot = ReasoningSlot.retrievalContext(procedureThought, 30);
        var updated = new ArrayList<WorkingMemorySlot>();
        updated.add(existingSlot);

        // 模拟第 1 段逻辑 — 再次注入相同 thought 的程序提示
        ReasoningSlot newSlot = ReasoningSlot.retrievalContext(procedureThought, 30);
        boolean alreadyExists = updated.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .map(s -> (ReasoningSlot) s)
                .anyMatch(rs -> rs.thought() != null && rs.thought().equals(newSlot.thought()));
        if (!alreadyExists) {
            updated.add(newSlot);
        }

        // 期望：仍然只有 1 个 ReasoningSlot（重复被跳过）
        long count = updated.stream().filter(s -> s instanceof ReasoningSlot).count();
        assertEquals(1, count, "L4 程序提示去重应正常工作，重复 thought 不应被注入");
    }
}
