package com.lifepilot.interaction.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * 消息内容 sealed interface，穷举所有消息类型。
 *
 * <p>通过 sealed interface + record 模式确保类型安全和不可变性，
 * switch 表达式可在编译时检查完整性。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface MessageContent
        permits MessageContent.TextMessage,
                MessageContent.CommandMessage,
                MessageContent.FileMessage,
                MessageContent.CardMessage,
                MessageContent.EventMessage {

    /**
     * 返回消息内容的纯文本表示。
     *
     * @return 非 null 的纯文本字符串
     */
    String toPlainText();

    /**
     * 纯文本消息。
     *
     * @param text 文本内容，不允许为 null 或空白
     * @author zsg
     * @since 2026-02-25
     */
    record TextMessage(String text) implements MessageContent {

        /**
         * 紧凑构造器：验证文本非空非 blank。
         */
        public TextMessage {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本消息内容不允许为空或空白");
            }
        }

        @Override
        public String toPlainText() {
            return text;
        }
    }

    /**
     * 命令消息，以 / 开头的命令文本。
     *
     * @param command 命令名（不含 / 前缀）
     * @param args    命令参数列表（不可变）
     * @param rawText 原始文本
     * @author zsg
     * @since 2026-02-25
     */
    record CommandMessage(String command, List<String> args, String rawText) implements MessageContent {

        /**
         * 紧凑构造器：防御性拷贝参数列表。
         */
        public CommandMessage {
            args = List.copyOf(args);
        }

        /**
         * 从以 / 开头的原始文本解析命令名和参数列表。
         *
         * @param rawText 原始文本，必须以 / 开头
         * @return 解析后的 CommandMessage
         * @throws IllegalArgumentException 文本不以 / 开头
         */
        public static CommandMessage parse(String rawText) {
            if (rawText == null || !rawText.startsWith("/")) {
                throw new IllegalArgumentException("命令文本必须以 / 开头: " + rawText);
            }
            String stripped = rawText.substring(1).strip();
            String[] parts = stripped.split("\\s+");
            String command = parts[0];
            List<String> args = parts.length > 1
                    ? List.of(Arrays.copyOfRange(parts, 1, parts.length))
                    : List.of();
            return new CommandMessage(command, args, rawText);
        }

        @Override
        public String toPlainText() {
            return rawText;
        }
    }

    /**
     * 文件消息。
     *
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @param data     文件数据
     * @param caption  文件说明（可空）
     * @author zsg
     * @since 2026-02-25
     */
    record FileMessage(String fileName, String mimeType, byte[] data, @Nullable String caption) implements MessageContent {

        @Override
        public String toPlainText() {
            return caption != null ? "[文件: %s] %s".formatted(fileName, caption)
                                   : "[文件: %s]".formatted(fileName);
        }
    }

    /**
     * 卡片消息。
     *
     * @param title       卡片标题
     * @param description 卡片描述
     * @param actions     卡片操作列表（不可变）
     * @author zsg
     * @since 2026-02-25
     */
    record CardMessage(String title, String description, List<CardAction> actions) implements MessageContent {

        /**
         * 紧凑构造器：防御性拷贝操作列表。
         */
        public CardMessage {
            actions = List.copyOf(actions);
        }

        @Override
        public String toPlainText() {
            return "[卡片: %s] %s".formatted(title, description);
        }

        /**
         * 卡片操作。
         *
         * @param label  操作标签
         * @param action 操作标识
         * @author zsg
         * @since 2026-02-25
         */
        public record CardAction(String label, String action) {}
    }

    /**
     * 事件消息。
     *
     * @param eventType 事件类型
     * @param payload   事件负载数据（不可变）
     * @author zsg
     * @since 2026-02-25
     */
    record EventMessage(String eventType, Map<String, Object> payload) implements MessageContent {

        /**
         * 紧凑构造器：防御性拷贝负载数据。
         */
        public EventMessage {
            payload = Map.copyOf(payload);
        }

        @Override
        public String toPlainText() {
            return "[事件: %s]".formatted(eventType);
        }
    }
}
