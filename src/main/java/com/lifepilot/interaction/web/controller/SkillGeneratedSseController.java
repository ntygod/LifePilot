package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.config.WebProperties;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.skill.event.SkillGeneratedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Skill 自动生成事件 SSE 实时推送控制器。
 *
 * <p>C.5 为前端自生成提示（"AI 帮你准备了 XX 技能"）提供订阅通道。连接建立后不推送初始
 * 快照（自生成事件具有"瞬时通知"语义，不需要补发历史）；后续
 * {@link SkillGeneratedEvent} 通过 {@link org.springframework.context.ApplicationEventPublisher}
 * 发布后由本类 {@link EventListener} 监听并广播到所有订阅者。</p>
 *
 * <p>复用 {@link SseSessionManager#createNotificationEmitter} +
 * {@link SseSessionManager#broadcastByPrefix}：SseEmitter 由 manager 集中管理并在
 * 完成/超时/异常时自动清理，无需本类维护连接列表。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@RestController
@RequestMapping("/api/skills")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class SkillGeneratedSseController {

    private static final Logger log = LoggerFactory.getLogger(SkillGeneratedSseController.class);

    /** Skill 事件 SSE 连接 streamId 前缀；{@link SseSessionManager#broadcastByPrefix} 据此筛选订阅者。 */
    static final String STREAM_ID_PREFIX = "skill-events-";

    private final SseSessionManager sseSessionManager;
    private final WebProperties webProperties;

    public SkillGeneratedSseController(SseSessionManager sseSessionManager,
                                        WebProperties webProperties) {
        this.sseSessionManager = sseSessionManager;
        this.webProperties = webProperties;
    }

    /**
     * SSE 订阅端点 — 实时推送 Skill 自动生成完成事件。
     *
     * <p>超时复用 {@code lifepilot.web.sse.mcp-status-timeout}（默认 30 分钟）——
     * 两者同为"长连接 + 低频广播"形态，避免为每个通知通道单独加一项配置。</p>
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        var streamId = STREAM_ID_PREFIX + UUID.randomUUID();
        var timeout = webProperties.sse().mcpStatusTimeout();
        var emitter = sseSessionManager.createNotificationEmitter(streamId, timeout);
        log.info("Skill 事件 SSE 连接建立: streamId={}", streamId);
        return emitter;
    }

    /**
     * 监听 {@link SkillGeneratedEvent}，广播到所有 Skill 事件 SSE 订阅者。
     *
     * <p>payload 采用扁平 Map 结构（{@code type + skillName + sourceType + at}）以便前端
     * 直接按 {@code type} 字段 dispatch；不直接序列化 {@code SkillGeneratedEvent} record
     * 是为了：(1) at 强制 ISO-8601 字符串化，前端零依赖反序列化；
     * (2) 明确 type 字段，后续扩展其他 Skill 事件类型（例如 SKILL_IMPORTED）时 payload 结构稳定。</p>
     *
     * @param event Skill 自动生成完成事件
     */
    @EventListener
    public void onSkillGenerated(SkillGeneratedEvent event) {
        var payload = Map.of(
                "type", SseEventType.SKILL_GENERATED,
                "skillName", event.skillName(),
                "sourceType", event.sourceType().name(),
                "at", event.at().toString()
        );
        sseSessionManager.broadcastByPrefix(STREAM_ID_PREFIX, SseEventType.SKILL_GENERATED, payload);
        log.debug("Skill 自动生成事件已广播: skillName={}, sourceType={}",
                event.skillName(), event.sourceType());
    }
}
