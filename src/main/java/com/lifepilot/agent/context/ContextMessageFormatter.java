package com.lifepilot.agent.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文消息格式化器，负责把消息流序列化为更易读的调试文本。
 *
 * @author zsg
 * @since 2026-03-24
 */
public final class ContextMessageFormatter {

    private static final Pattern TAGGED_BLOCK_PATTERN = Pattern.compile(
            "^<([a-z][a-z0-9_]*)>\\s*(.*?)\\s*</\\1>$",
            Pattern.DOTALL
    );
    private static final Pattern MULTI_TAGGED_BLOCK_PATTERN = Pattern.compile(
            "<([a-z][a-z0-9_]*)>\\s*(.*?)\\s*</\\1>",
            Pattern.DOTALL
    );

    private ContextMessageFormatter() {
    }

    public static String serializeForPreview(@Nullable List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (Message message : messages) {
            appendPreviewLine(buffer, message);
        }
        return buffer.toString().trim();
    }

    public static String serializeForDebug(@Nullable List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                TaggedBlock taggedBlock = parseTaggedBlock(assistantMessage.getText());
                if (taggedBlock != null) {
                    buffer.append(taggedBlock.rawText()).append('\n');
                    continue;
                }
            }
            if (message instanceof UserMessage userMessage) {
                var taggedBlocks = parseTaggedBlocks(userMessage.getText());
                if (!taggedBlocks.isEmpty()) {
                    buffer.append(taggedBlocks.stream()
                            .map(TaggedBlock::rawText)
                            .reduce((left, right) -> left + "\n\n" + right)
                            .orElse(""))
                            .append('\n');
                    continue;
                }
            }
            buffer.append("  [").append(i).append("] ")
                    .append(debugHeader(message))
                    .append(": ")
                    .append(debugBody(message))
                    .append('\n');
        }
        return buffer.toString().trim();
    }

    private static void appendPreviewLine(StringBuilder buffer, Message message) {
        switch (message) {
            case SystemMessage systemMessage ->
                    buffer.append("[system] ").append(safeText(systemMessage.getText())).append('\n');
            case UserMessage userMessage -> {
                var taggedBlocks = parseTaggedBlocks(userMessage.getText());
                if (!taggedBlocks.isEmpty()) {
                    taggedBlocks.forEach(block -> buffer.append(block.rawText()).append('\n'));
                } else {
                    buffer.append("[user] ").append(safeText(userMessage.getText())).append('\n');
                }
            }
            case AssistantMessage assistantMessage -> appendAssistantPreview(buffer, assistantMessage);
            case ToolResponseMessage toolResponseMessage -> toolResponseMessage.getResponses().forEach(response ->
                    buffer.append("[tool_result] ")
                            .append(response.name())
                            .append(' ')
                            .append(safeText(response.responseData()))
                            .append('\n'));
            default -> buffer.append(message).append('\n');
        }
    }

    private static void appendAssistantPreview(StringBuilder buffer, AssistantMessage assistantMessage) {
        TaggedBlock taggedBlock = parseTaggedBlock(assistantMessage.getText());
        if (taggedBlock != null) {
            buffer.append(taggedBlock.rawText()).append('\n');
            return;
        }

        if (assistantMessage.hasToolCalls()) {
            assistantMessage.getToolCalls().forEach(toolCall -> buffer
                    .append("[tool_call] ")
                    .append(toolCall.name())
                    .append(' ')
                    .append(safeText(toolCall.arguments()))
                    .append('\n'));
        }
        if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
            buffer.append("[assistant] ").append(assistantMessage.getText()).append('\n');
        }
    }

    private static String debugHeader(Message message) {
        return switch (message) {
            case SystemMessage ignored -> "SystemMessage";
            case UserMessage ignored -> "UserMessage";
            case AssistantMessage ignored -> "AssistantMessage";
            case ToolResponseMessage ignored -> "ToolResultMessage";
            default -> message.getClass().getSimpleName();
        };
    }

    private static String debugBody(Message message) {
        return switch (message) {
            case SystemMessage systemMessage -> safeText(systemMessage.getText());
            case UserMessage userMessage -> safeText(userMessage.getText());
            case AssistantMessage assistantMessage -> {
                String text = safeText(assistantMessage.getText());
                if (assistantMessage.hasToolCalls()) {
                    yield text + (text.isBlank() ? "" : " ")
                            + "[tool_calls=" + assistantMessage.getToolCalls().size() + "]";
                }
                yield text;
            }
            case ToolResponseMessage toolResponseMessage -> toolResponseMessage.getResponses().stream()
                    .map(response -> response.name() + ": " + safeText(response.responseData()))
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            default -> message.toString();
        };
    }

    @Nullable
    public static TaggedBlock parseTaggedBlock(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = TAGGED_BLOCK_PATTERN.matcher(text.strip());
        if (!matcher.matches()) {
            return null;
        }
        return new TaggedBlock(
                matcher.group(1),
                matcher.group(2).strip(),
                text.strip()
        );
    }

    public static List<TaggedBlock> parseTaggedBlocks(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String stripped = text.strip();
        Matcher matcher = MULTI_TAGGED_BLOCK_PATTERN.matcher(stripped);
        List<TaggedBlock> blocks = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            String gap = stripped.substring(cursor, matcher.start());
            if (!gap.isBlank()) {
                return List.of();
            }
            blocks.add(new TaggedBlock(
                    matcher.group(1),
                    matcher.group(2).strip(),
                    matcher.group().strip()
            ));
            cursor = matcher.end();
        }
        if (blocks.isEmpty()) {
            return List.of();
        }
        if (!stripped.substring(cursor).isBlank()) {
            return List.of();
        }
        return List.copyOf(blocks);
    }

    public static String serializeHistoryTranscript(@Nullable List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Message> filteredMessages = messages.stream()
                .filter(message -> !isHistoryTranscriptMarker(message))
                .toList();
        return serializeForPreview(filteredMessages);
    }

    private static boolean isHistoryTranscriptMarker(Message message) {
        if (!(message instanceof AssistantMessage assistantMessage)) {
            return false;
        }
        TaggedBlock taggedBlock = parseTaggedBlock(assistantMessage.getText());
        return taggedBlock != null && "history_transcript".equals(taggedBlock.tagName());
    }

    private static String safeText(@Nullable String text) {
        return text == null ? "" : text;
    }

    public record TaggedBlock(String tagName, String content, String rawText) {
    }
}
