package com.lifepilot.agent.proactive.model;

import com.lifepilot.notification.Urgency;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 泛化信号记录 — 任何 {@link com.lifepilot.agent.proactive.signal.SignalSource} 产生的信号均使用此载体。
 *
 * <p>包含类型标识、紧急度、摘要、来源标识、主体标识和元数据。
 * {@code metadata} 在构造时通过 {@link Map#copyOf(Map)} 确保不可变。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@Builder(toBuilder = true)
public record Signal(
        /** 类型标识（如 "deadline_reminder"）。 */
        String typeId,

        /** 紧急度。 */
        Urgency urgency,

        /** 摘要。 */
        String summary,

        /** 来源标识（对应 SignalSource.id()）。 */
        String sourceId,

        /** 主体标识（可选，如具体待办 ID）。 */
        @Nullable String subjectId,

        /** 元数据。 */
        Map<String, Object> metadata
) {

    /** 紧凑构造函数 — 确保 metadata 不可变。 */
    public Signal {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
