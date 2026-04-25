package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.suspend.event.BrowserTakeoverCompletedEvent;
import com.lifepilot.interaction.web.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器人工接管恢复入口 — 用户在浏览器完成登录/验证码/人机验证后，
 * 前端 POST 本端点触发 {@link BrowserTakeoverCompletedEvent}，
 * 由 {@link com.lifepilot.agent.suspend.AgentResumeListener} 按 sessionId 匹配挂起的 Agent 并恢复。
 *
 * <p>turnId 放在 path 用于前端构造 URL 和后端日志关联；sessionId 才是真正的匹配键
 * （对应 SuspendReason.BrowserTakeover.sessionId）。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@RestController
@RequestMapping("/api/agent/browser-takeover")
public class BrowserTakeoverController {

    private static final Logger log = LoggerFactory.getLogger(BrowserTakeoverController.class);

    private final ApplicationEventPublisher publisher;

    public BrowserTakeoverController(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 恢复因浏览器接管而挂起的 Agent。
     *
     * @param turnId    所在对话 turn 的 ID（用于前端构 URL 和日志追踪）
     * @param sessionId 浏览器会话 ID，匹配 SuspendReason.BrowserTakeover.sessionId
     * @param cancelled 是否用户取消任务（true 取消，false 正常继续）
     * @param note      用户备注（可空）
     */
    @PostMapping("/{turnId}/resume")
    public ResponseEntity<ApiResponse<Void>> resume(
            @PathVariable String turnId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "false") boolean cancelled,
            @RequestParam(required = false) @Nullable String note) {
        log.info("收到浏览器接管恢复请求：turnId={}, sessionId={}, cancelled={}", turnId, sessionId, cancelled);
        publisher.publishEvent(new BrowserTakeoverCompletedEvent(sessionId, cancelled, note));
        return ResponseEntity.accepted().body(ApiResponse.ok());
    }
}
