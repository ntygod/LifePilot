package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.A2aMessage;
import com.lifepilot.a2a.model.A2aPart;
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
        for (int i = 0; i < message.parts().size(); i++) {
            String partError = validatePart(message.parts().get(i), i);
            if (partError != null) {
                return partError;
            }
        }
        return null;
    }

    /**
     * 校验单个 A2aPart 内容完整性。
     *
     * @param part  待校验片段
     * @param index 片段索引（用于错误提示）
     * @return 校验错误描述（通过返回 null）
     */
    @Nullable
    private static String validatePart(A2aPart part, int index) {
        return switch (part) {
            case A2aPart.Text text -> {
                if (text.text() == null || text.text().isBlank()) {
                    yield "parts[" + index + "]: text 内容不能为空";
                }
                yield null;
            }
            case A2aPart.File file -> {
                if (file.file() == null) {
                    yield "parts[" + index + "]: file 字段不能为空";
                } else if ((file.file().bytes() == null || file.file().bytes().isBlank())
                        && (file.file().uri() == null || file.file().uri().isBlank())) {
                    yield "parts[" + index + "]: file 必须包含 bytes 或 uri";
                }
                yield null;
            }
            case A2aPart.Data data -> {
                if (data.data() == null || data.data().isEmpty()) {
                    yield "parts[" + index + "]: data 不能为空";
                }
                yield null;
            }
        };
    }
}
