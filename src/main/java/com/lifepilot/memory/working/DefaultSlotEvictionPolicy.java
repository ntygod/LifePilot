package com.lifepilot.memory.working;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * 默认的工作记忆淘汰策略。
 *
 * <p>实现与原先 {@link WorkingMemory} 内部逻辑等价：</p>
 * <ul>
 *   <li>跳过被固定（pinned）的 {@link ConversationSlot}</li>
 *   <li>类型优先级：ReasoningSlot → ToolResultSlot → ConversationSlot</li>
 *   <li>同类型内按 {@link WorkingMemorySlot#importance()} 升序淘汰</li>
 * </ul>
 */
public class DefaultSlotEvictionPolicy implements SlotEvictionPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultSlotEvictionPolicy.class);

    @Override
    public WorkingMemorySlot selectEvictionCandidate(String sessionId,
                                                     List<WorkingMemorySlot> slots,
                                                     int currentTokenUsage,
                                                     int tokenBudget) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        synchronized (slots) {
            var candidate = slots.stream()
                    .filter(s -> !(s instanceof ConversationSlot cs && cs.isPinned()))
                    .min(Comparator.comparingInt(this::typePriority)
                            .thenComparing(WorkingMemorySlot::importance))
                    .orElse(null);

            if (candidate == null) {
                log.debug("默认淘汰策略未找到可淘汰槽位: sessionId={}", sessionId);
            }
            return candidate;
        }
    }

    /**
     * 类型淘汰优先级：ReasoningSlot(0) → ToolResultSlot(1) → ConversationSlot(2)。
     */
    private int typePriority(WorkingMemorySlot slot) {
        if (slot instanceof ReasoningSlot) {
            return 0;
        }
        if (slot instanceof ToolResultSlot) {
            return 1;
        }
        return 2;
    }
}

