package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 剪贴板意图行为插件 — 从剪贴板缓冲区检测意图并生成上下文建议。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ClipboardBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ClipboardBehavior.class);
    private final ClipboardIntentBuffer buffer;

    public ClipboardBehavior(ClipboardIntentBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public String name() { return "clipboard"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        var intents = buffer.drainAll();
        if (intents.isEmpty()) return List.of();

        var candidates = new ArrayList<ProactiveCandidate>();
        for (var intent : intents) {
            String title = describeIntent(intent);
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    intent.intentType().name().toLowerCase() + "-" + intent.value(),
                    title, 0.6f,
                    intent.intentType().name(), intent));
        }
        log.debug("ClipboardBehavior.detect: intents={}", candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof ReminderClipboardIntent intent)) continue;
            String suggestion = generateSuggestion(intent);
            actions.add(new ProactiveAction(candidate, suggestion, DeliveryLevel.NOTIFY, intent));
        }
        return actions;
    }

    private String describeIntent(ReminderClipboardIntent intent) {
        return switch (intent.intentType()) {
            case TRACKING_NUMBER -> "快递单号: " + intent.value();
            case FLIGHT_NUMBER -> "航班号: " + intent.value();
            case TRAIN_NUMBER -> "火车车次: " + intent.value();
            case URL -> "链接: " + truncate(intent.value(), 40);
            case PHONE -> "电话号码: " + intent.value();
        };
    }

    private String generateSuggestion(ReminderClipboardIntent intent) {
        return switch (intent.intentType()) {
            case TRACKING_NUMBER -> "检测到快递单号 " + intent.value() + "，要帮你查一下物流状态吗？";
            case FLIGHT_NUMBER -> "检测到航班号 " + intent.value() + "，要帮你查一下航班动态吗？";
            case TRAIN_NUMBER -> "检测到火车车次 " + intent.value() + "，要帮你查一下列车信息吗？";
            case URL -> "检测到一个链接，要帮你看看内容吗？";
            case PHONE -> "检测到电话号码 " + intent.value() + "，要帮你查一下归属地吗？";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
