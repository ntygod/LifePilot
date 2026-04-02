package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.A2aMessage;
import org.springframework.lang.Nullable;

/**
 * A2A 消息请求校验工具。
 *
 * <p>校验入站 {@link A2aMessage} 的必填字段完整性。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public final class A2aMessageValidator {

    private A2aMessageValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验 A2aMessage。
     *
     * @param message 待校验消息
     * @return 校验错误描述（通过返回 null）
     */
    @Nullable
    public static String validate(A2aMessage message) {
        if (message == null) {
            return "消息体不能为空";
        }
        if (message.messageId() == null || message.messageId().isBlank()) {
            return "messageId 不能为空";
        }
        if (message.role() == null) {
            return "role 不能为空";
        }
        if (message.parts() == null || message.parts().isEmpty()) {
            return "parts 不能为空，至少包含一个内容片段";
        }
        return null;
    }
}
