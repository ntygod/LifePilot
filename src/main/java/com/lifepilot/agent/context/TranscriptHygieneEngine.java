package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        Set<String> pendingToolNames = new LinkedHashSet<>();
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
                    pendingToolNames.clear();
                    cleaned.add(userMessage);
                }
                case AssistantMessage assistantMessage -> {
                    if (!assistantMessage.hasToolCalls()) {
                        if (messageBuildConfig().isDropEmptyAssistantMessages()
                                && isBlank(assistantMessage.getText())) {
                            droppedEmptyAssistant++;
                            continue;
                        }
                        pendingToolNames.clear();
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

                    pendingToolNames.clear();
                    toolCalls.stream()
                            .map(AssistantMessage.ToolCall::name)
                            .filter(Objects::nonNull)
                            .forEach(pendingToolNames::add);

                    if (toolCalls.isEmpty()) {
                        cleaned.add(new AssistantMessage(assistantMessage.getText()));
                    } else {
                        cleaned.add(AssistantMessage.builder()
                                .content(assistantMessage.getText() != null ? assistantMessage.getText() : "")
                                .toolCalls(toolCalls)
                                .build());
                    }
                }
                case ToolResponseMessage toolResponseMessage -> {
                    if (pendingToolNames.isEmpty() && messageBuildConfig().isDropOrphanToolResponses()) {
                        droppedOrphanToolResponses++;
                        continue;
                    }

                    List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses().stream()
                            .filter(Objects::nonNull)
                            .filter(response -> !isBlank(response.name()))
                            .filter(response -> pendingToolNames.isEmpty()
                                    || pendingToolNames.contains(response.name())
                                    || !messageBuildConfig().isDropOrphanToolResponses())
                            .toList();

                    if (responses.isEmpty() && messageBuildConfig().isDropOrphanToolResponses()) {
                        droppedOrphanToolResponses++;
                        continue;
                    }

                    responses.stream()
                            .map(ToolResponseMessage.ToolResponse::name)
                            .filter(Objects::nonNull)
                            .forEach(pendingToolNames::remove);

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
}
