package com.lifepilot.agent.task.reminder.tracking;

import org.springframework.lang.Nullable;

/**
 * 短信转发请求 — 手机端 Tasker/快捷指令转发来的短信数据。
 *
 * @param sender     发件人（如"【12306】""【菜鸟裹裹】"）
 * @param body       短信正文
 * @param receivedAt 接收时间（ISO 8601）
 * @author zsg
 * @since 2026-04-05
 */
public record SmsSignalRequest(
        String sender,
        String body,
        @Nullable String receivedAt
) {
}
