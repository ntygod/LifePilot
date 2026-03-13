package com.lifepilot.notification;

import java.util.List;

/**
 * 统一通知服务接口。
 *
 * <p>定义在 {@code com.lifepilot.notification} 包中，供工作流引擎、
 * 主动推理及未来其他模块复用通知发送能力。
 *
 * @author zsg
 * @since 2026-03-13
 */
public interface NotificationService {

    /**
     * 发送通知。
     *
     * <p>根据 {@link NotificationRequest#urgency()} 路由通知：
     * HIGH / MEDIUM 通过 ChannelAdapter 实时推送，LOW 入队被动通知队列。
     * 每个成功发送的渠道生成独立的通知记录。
     *
     * @param request 通知请求
     * @return 通知 ID 列表（每个成功渠道一条记录）
     */
    List<String> send(NotificationRequest request);
}
