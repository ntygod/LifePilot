package com.lifepilot.agent.task.proactive;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 四级投递引擎。
 *
 * <p>根据 {@link DeliveryLevel} 将主动行为动作路由到对应投递通道：
 * SILENT（仅记录）→ QUEUE（排队）→ NOTIFY（通知）→ INTERRUPT（打断）。
 * 当 {@link NotificationService} 不可用时，NOTIFY/INTERRUPT 降级为 QUEUE。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class DeliveryEngine {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEngine.class);
    private static final String PROACTIVE_TYPE = "proactive_action";

    @Nullable
    private final NotificationService notificationService;
    private final QueuedActionRepository queuedActionRepository;

    public DeliveryEngine(@Nullable NotificationService notificationService,
                          QueuedActionRepository queuedActionRepository) {
        this.notificationService = notificationService;
        this.queuedActionRepository = queuedActionRepository;
        if (notificationService == null) {
            log.warn("投递引擎: NotificationService 不可用，NOTIFY/INTERRUPT 将降级为 QUEUE");
        }
    }

    /**
     * 按指定级别投递主动行为动作。
     */
    public DeliveryResult deliver(ProactiveAction action, DeliveryLevel level, String userId) {
        return switch (level) {
            case SILENT -> deliverSilent(action);
            case QUEUE -> deliverQueue(action, userId);
            case NOTIFY -> deliverNotification(action, userId, false);
            case INTERRUPT -> deliverNotification(action, userId, true);
        };
    }

    private DeliveryResult deliverSilent(ProactiveAction action) {
        log.debug("投递引擎: SILENT behavior={} topic={}",
                action.candidate().behaviorName(), action.candidate().topicKey());
        return new DeliveryResult(null, DeliveryLevel.SILENT, Instant.now());
    }

    private DeliveryResult deliverQueue(ProactiveAction action, String userId) {
        // 用 userId+topicKey 生成确定性 id，同一话题的队列条目走 UPSERT 去重
        var stableId = UUID.nameUUIDFromBytes(
                (userId + ":" + action.candidate().topicKey()).getBytes()).toString();
        var record = new QueuedActionRecord(
                stableId,
                userId,
                action.candidate().behaviorName(),
                action.candidate().topicKey(),
                action.candidate().title(),
                action.content(),
                action.candidate().score(),
                null,
                false,
                Instant.now(),
                null
        );
        queuedActionRepository.save(record);
        log.debug("投递引擎: QUEUE behavior={} topic={}",
                action.candidate().behaviorName(), action.candidate().topicKey());
        return new DeliveryResult(null, DeliveryLevel.QUEUE, Instant.now());
    }

    private DeliveryResult deliverNotification(ProactiveAction action, String userId, boolean interrupt) {
        if (notificationService == null) {
            log.debug("投递引擎: NotificationService 不可用，{} 降级为 QUEUE", interrupt ? "INTERRUPT" : "NOTIFY");
            return deliverQueue(action, userId);
        }
        var candidate = action.candidate();
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("behaviorName", candidate.behaviorName());
        metadata.put("topicKey", candidate.topicKey());
        metadata.put("score", String.format("%.3f", candidate.score()));
        metadata.put("deliveryLevel", interrupt ? "INTERRUPT" : "NOTIFY");

        String title = interrupt ? "【主动提醒】" : "【轻提醒】";
        String text = title + "\n" + action.content();

        List<String> ids = notificationService.send(new NotificationRequest(
                userId,
                new ResponseContent.TextContent(text),
                "WEB",
                PROACTIVE_TYPE,
                metadata
        ));

        String notificationId = ids.isEmpty() ? null : ids.getFirst();
        log.debug("投递引擎: {} behavior={} topic={} notificationId={}",
                interrupt ? "INTERRUPT" : "NOTIFY",
                candidate.behaviorName(), candidate.topicKey(), notificationId);
        return new DeliveryResult(notificationId, interrupt ? DeliveryLevel.INTERRUPT : DeliveryLevel.NOTIFY,
                Instant.now());
    }
}
