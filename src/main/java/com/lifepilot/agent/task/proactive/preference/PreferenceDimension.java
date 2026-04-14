package com.lifepilot.agent.task.proactive.preference;

/**
 * 偏好维度 — 五维偏好模型。
 *
 * <p>TIMING: 时间偏好（何时推送最受欢迎）。
 * DOMAIN: 领域偏好（用户更关注哪些话题领域）。
 * AUTONOMY: 自主度偏好（用户倾向何种交互深度）。
 * STYLE: 风格偏好（通知风格、语气）。
 * CONTEXT: 上下文偏好（何种场景下更愿意接收）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum PreferenceDimension {
    TIMING,
    DOMAIN,
    AUTONOMY,
    STYLE,
    CONTEXT
}
