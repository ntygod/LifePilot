package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.DeliveryLevel;
import com.lifepilot.agent.task.proactive.ProactiveAction;
import com.lifepilot.agent.task.proactive.ProactiveBehavior;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import com.lifepilot.agent.task.proactive.ProactiveMemoryBridge;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 记忆关注行为插件 — 消费记忆注意力清单，生成低打扰主动建议。
 *
 * @author zsg
 * @since 2026-06-19
 */
public class MemoryAttentionBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(MemoryAttentionBehavior.class);
    private static final int MAX_ATTENTION_ITEMS = 5;

    private final ProactiveMemoryBridge memoryBridge;

    public MemoryAttentionBehavior(ProactiveMemoryBridge memoryBridge) {
        this.memoryBridge = memoryBridge;
    }

    @Override
    public String name() {
        return "memory-attention";
    }

    @Override
    public BehaviorLayer layer() {
        return BehaviorLayer.FACT_DRIVEN;
    }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        var items = memoryBridge.getAttentionItems(MAX_ATTENTION_ITEMS);
        if (items.isEmpty()) {
            return List.of();
        }

        var candidates = new ArrayList<ProactiveCandidate>();
        for (var item : items) {
            if (!isActionable(item)) {
                continue;
            }
            String topicKey = "memory-attention:" + item.kind().name().toLowerCase(Locale.ROOT)
                    + ":" + item.entityId();
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(),
                    name(),
                    topicKey,
                    item.name(),
                    item.score(),
                    item.reason(),
                    item));
        }
        log.debug("MemoryAttentionBehavior.detect: items={}, candidates={}", items.size(), candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof AttentionItem item)) {
                continue;
            }
            String content = buildContent(item);
            DeliveryLevel level = suggestedLevel(item);
            actions.add(new ProactiveAction(candidate, content, level, item));
        }
        return actions;
    }

    private boolean isActionable(AttentionItem item) {
        return switch (item.kind()) {
            case DUE_SOON, EXPIRING, NEGLECTED, CONNECTION -> true;
            case EVOLVING -> false;
        };
    }

    private DeliveryLevel suggestedLevel(AttentionItem item) {
        return switch (item.kind()) {
            case DUE_SOON, EXPIRING -> item.score() >= 0.65f ? DeliveryLevel.NOTIFY : DeliveryLevel.QUEUE;
            case NEGLECTED, CONNECTION, EVOLVING -> DeliveryLevel.QUEUE;
        };
    }

    private String buildContent(AttentionItem item) {
        return switch (item.kind()) {
            case DUE_SOON, EXPIRING -> item.reason() + "，要现在看一下吗？";
            case NEGLECTED -> "「" + item.name() + "」有段时间没关注了，要不要我帮你整理下一步？";
            case CONNECTION -> buildConnectionContent(item);
            case EVOLVING -> item.reason();
        };
    }

    private String buildConnectionContent(AttentionItem item) {
        if (item.pathLabels() != null && !item.pathLabels().isEmpty()) {
            return "我注意到一个关联：" + item.pathLabels().getFirst() + "，要不要一起看看？";
        }
        return "我注意到「" + item.name() + "」可能和你当前关注的内容有关，要不要一起看看？";
    }
}
