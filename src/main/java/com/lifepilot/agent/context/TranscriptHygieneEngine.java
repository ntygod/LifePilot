package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * TranscriptHygieneEngine 负责把 provider 消息序列修正为更稳定的可发送形式。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class TranscriptHygieneEngine {

    private final AgentConfigProperties config;

    public record HygieneReport(
            int originalCount,
            int cleanedCount,
            int droppedEmptyAssistantMessages,
            int droppedOrphanToolResponses,
            int droppedAdditionalSystemMessages,
            int droppedEmptyUserMessages
    ) {
        public boolean hasRepairs() {
            return originalCount != cleanedCount
                    || droppedEmptyAssistantMessages > 0
                    || droppedOrphanToolResponses > 0
                    || droppedAdditionalSystemMessages > 0
                    || droppedEmptyUserMessages > 0;
        }
    }

    public record HygieneResult(
            List<Message> messages,
            HygieneReport report
    ) {
        public HygieneResult {
            messages = List.copyOf(messages);
        }
    }

    public TranscriptHygieneEngine(AgentConfigProperties config) {
        this.config = Objects.requireNonNull(config);
    }

    private record PendingToolCall(@Nullable String id, String name) {
    }

    public HygieneResult clean(List<Message> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty() || !messageBuildConfig().isHygieneEnabled()) {
            return new HygieneResult(
                    rawMessages == null ? List.of() : List.copyOf(rawMessages),
                    new HygieneReport(
                            rawMessages == null ? 0 : rawMessages.size(),
                            rawMessages == null ? 0 : rawMessages.size(),
                            0,
                            0,
                            0,
                            0
                    )
            );
        }

        List<Message> cleaned = new ArrayList<>();
        List<PendingToolCall> pendingToolCalls = new ArrayList<>();
        boolean systemSeen = false;
        int droppedEmptyAssistant = 0;
        int droppedOrphanToolResponses = 0;
        int droppedAdditionalSystemMessages = 0;
        int droppedEmptyUserMessages = 0;

        for (Message rawMessage : rawMessages) {
            if (rawMessage == null) {
                continue;
            }
            switch (rawMessage) {
                case SystemMessage systemMessage -> {
                    if (isBlank(systemMessage.getText())) {
                        droppedAdditionalSystemMessages++;
                        continue;
                    }
                    if (systemSeen && messageBuildConfig().isKeepOnlyFirstSystemMessage()) {
                        droppedAdditionalSystemMessages++;
                        continue;
                    }
                    cleaned.add(systemMessage);
                    systemSeen = true;
                }
                case UserMessage userMessage -> {
                    if (isBlank(userMessage.getText()) && userMessage.getMedia().isEmpty()) {
                        droppedEmptyUserMessages++;
                        continue;
                    }
                    pendingToolCalls.clear();
                    cleaned.add(userMessage);
                }
                case AssistantMessage assistantMessage -> {
                    if (!assistantMessage.hasToolCalls()) {
                        if (messageBuildConfig().isDropEmptyAssistantMessages()
                                && isBlank(assistantMessage.getText())) {
                            droppedEmptyAssistant++;
                            continue;
                        }
                        pendingToolCalls.clear();
                        cleaned.add(assistantMessage);
                        continue;
                    }

                    List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls().stream()
                            .filter(Objects::nonNull)
                            .filter(toolCall -> !isBlank(toolCall.name()))
                            .toList();

                    if (toolCalls.isEmpty() && isBlank(assistantMessage.getText())) {
                        droppedEmptyAssistant++;
                        continue;
                    }

                    pendingToolCalls.clear();
                    toolCalls.stream()
                            .map(this::toPendingToolCall)
                            .filter(Objects::nonNull)
                            .forEach(pendingToolCalls::add);

                    if (toolCalls.isEmpty()) {
                        cleaned.add(new AssistantMessage(assistantMessage.getText()));
                    } else {
                        cleaned.add(buildAssistantToolCallMessage(assistantMessage.getText(), toolCalls));
                    }
                }
                case ToolResponseMessage toolResponseMessage -> {
                    if (pendingToolCalls.isEmpty() && messageBuildConfig().isDropOrphanToolResponses()) {
                        droppedOrphanToolResponses++;
                        continue;
                    }

                    List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses().stream()
                            .filter(Objects::nonNull)
                            .filter(response -> !isBlank(response.name()) || !isBlank(response.id()))
                            .filter(response -> pendingToolCalls.isEmpty()
                                    || matchesPendingToolCall(response, pendingToolCalls)
                                    || !messageBuildConfig().isDropOrphanToolResponses())
                            .toList();

                    if (responses.isEmpty() && messageBuildConfig().isDropOrphanToolResponses()) {
                        droppedOrphanToolResponses++;
                        continue;
                    }

                    responses.stream()
                            .forEach(response -> consumePendingToolCall(response, pendingToolCalls));

                    cleaned.add(ToolResponseMessage.builder()
                            .responses(responses)
                            .build());
                }
                default -> cleaned.add(rawMessage);
            }
        }

        return new HygieneResult(
                cleaned,
                new HygieneReport(
                        rawMessages.size(),
                        cleaned.size(),
                        droppedEmptyAssistant,
                        droppedOrphanToolResponses,
                        droppedAdditionalSystemMessages,
                        droppedEmptyUserMessages
                )
        );
    }

    private AgentConfigProperties.ContextConfig.MessageBuildConfig messageBuildConfig() {
        AgentConfigProperties.ContextConfig.MessageBuildConfig messageBuild =
                config.getContext().getMessageBuild();
        return messageBuild != null
                ? messageBuild
                : new AgentConfigProperties.ContextConfig.MessageBuildConfig();
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private AssistantMessage buildAssistantToolCallMessage(@Nullable String content,
                                                           List<AssistantMessage.ToolCall> toolCalls) {
        var builder = AssistantMessage.builder().toolCalls(toolCalls);
        if (!isBlank(content)) {
            builder.content(content);
        }
        return builder.build();
    }

    @Nullable
    private PendingToolCall toPendingToolCall(AssistantMessage.ToolCall toolCall) {
        if (toolCall == null || isBlank(toolCall.name())) {
            return null;
        }
        return new PendingToolCall(blankToNull(toolCall.id()), toolCall.name());
    }

    private boolean matchesPendingToolCall(ToolResponseMessage.ToolResponse response,
                                           List<PendingToolCall> pendingToolCalls) {
        String responseId = blankToNull(response.id());
        if (responseId != null) {
            return pendingToolCalls.stream().anyMatch(pending -> responseId.equals(pending.id()));
        }
        String responseName = blankToNull(response.name());
        return responseName != null
                && pendingToolCalls.stream().anyMatch(pending -> responseName.equals(pending.name()));
    }

    private void consumePendingToolCall(ToolResponseMessage.ToolResponse response,
                                        List<PendingToolCall> pendingToolCalls) {
        String responseId = blankToNull(response.id());
        if (responseId != null) {
            for (int i = 0; i < pendingToolCalls.size(); i++) {
                PendingToolCall pending = pendingToolCalls.get(i);
                if (responseId.equals(pending.id())) {
                    pendingToolCalls.remove(i);
                    return;
                }
            }
        }

        String responseName = blankToNull(response.name());
        if (responseName == null) {
            return;
        }
        for (int i = 0; i < pendingToolCalls.size(); i++) {
            PendingToolCall pending = pendingToolCalls.get(i);
            if (responseName.equals(pending.name())) {
                pendingToolCalls.remove(i);
                return;
            }
        }
    }

    @Nullable
    private String blankToNull(@Nullable String value) {
        return isBlank(value) ? null : value;
    }
}
