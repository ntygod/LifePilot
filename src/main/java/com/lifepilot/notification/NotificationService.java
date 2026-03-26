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
     * <p>通知统一采用直接发送策略。
     * 每个成功发送的渠道生成独立的通知记录。
     *
     * @param request 通知请求
     * @return 通知 ID 列表（每个成功渠道一条记录）
     */
    List<String> send(NotificationRequest request);
}
