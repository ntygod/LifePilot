package com.lifepilot.agent.context;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ProviderMessageBuilder 负责把 Agent 上下文与 ReAct 步骤组装成 provider 消息序列。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class ProviderMessageBuilder {

    private final TranscriptHygieneEngine hygieneEngine;

    public record BuildResult(
            List<Message> messages,
            TranscriptHygieneEngine.HygieneReport hygieneReport
    ) {
        public BuildResult {
            messages = List.copyOf(messages);
        }
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine) {
        this.hygieneEngine = Objects.requireNonNull(hygieneEngine);
    }

    public BuildResult build(AssembledContext context, ReactAgentState state) {
        List<Message> rawMessages = new ArrayList<>();
        rawMessages.add(new SystemMessage(context.systemPrompt()));
        rawMessages.add(buildUserMessage(buildStructuredPrompt(context), context.mediaContents()));

        for (ReactStep step : state.steps()) {
            Message message = toMessage(step);
            if (message != null) {
                rawMessages.add(message);
            }
        }

        TranscriptHygieneEngine.HygieneResult hygieneResult = hygieneEngine.clean(rawMessages);
        return new BuildResult(hygieneResult.messages(), hygieneResult.report());
    }

    public String serializeForMultimodal(@Nullable List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder buffer = new StringBuilder();
        for (Message message : messages) {
            switch (message) {
                case SystemMessage systemMessage ->
                        appendSection(buffer, "system", systemMessage.getText());
                case UserMessage userMessage -> {
                    var taggedBlocks = ContextMessageFormatter.parseTaggedBlocks(userMessage.getText());
                    if (!taggedBlocks.isEmpty()) {
                        taggedBlocks.forEach(block -> buffer.append(block.rawText()).append("\n\n"));
                    } else {
                        appendSection(buffer, "user", userMessage.getText());
                    }
                }
                case AssistantMessage assistantMessage -> appendAssistantSection(buffer, assistantMessage);
                case ToolResponseMessage toolResponseMessage -> {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        appendSection(
                                buffer,
                                "tool_result:" + response.name(),
                                response.responseData()
                        );
                    }
                }
                default -> {
                    // 忽略当前未使用的消息类型
                }
            }
        }
        return buffer.toString().strip();
    }

    private void appendAssistantSection(StringBuilder buffer, AssistantMessage assistantMessage) {
        ContextMessageFormatter.TaggedBlock taggedBlock =
                ContextMessageFormatter.parseTaggedBlock(assistantMessage.getText());
        if (taggedBlock != null) {
            buffer.append(taggedBlock.rawText()).append("\n\n");
            return;
        }

        if (assistantMessage.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                appendSection(
                        buffer,
                        "tool_call:" + toolCall.name(),
                        toolCall.arguments()
                );
            }
        }

        if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
            appendSection(buffer, "assistant", assistantMessage.getText());
        }
    }

    private UserMessage buildUserMessage(String userPrompt, @Nullable List<MediaContent> mediaContents) {
        if (mediaContents == null || mediaContents.isEmpty()) {
            return new UserMessage(userPrompt);
        }
        UserMessage.Builder builder = UserMessage.builder().text(userPrompt);
        for (MediaContent mediaContent : mediaContents) {
            builder.media(new Media(
                    MimeTypeUtils.parseMimeType(mediaContent.mimeType()),
                    new ByteArrayResource(mediaContent.data())
            ));
        }
        return builder.build();
    }

    private String buildStructuredPrompt(AssembledContext context) {
        List<ContextMessageFormatter.TaggedBlock> promptBlocks =
                ContextMessageFormatter.parseTaggedBlocks(context.userPrompt());
        String runtimeBlock = findTaggedBlock(promptBlocks, "runtime_context");
        String currentRequestBlock = findTaggedBlock(promptBlocks, "current_request");

        List<String> sections = new ArrayList<>();
        appendSectionIfPresent(sections, runtimeBlock);
        context.contextMessages().stream()
                .map(this::extractRawTaggedContext)
                .filter(Objects::nonNull)
                .forEach(sections::add);
        promptBlocks.stream()
                .map(ContextMessageFormatter.TaggedBlock::rawText)
                .filter(raw -> !raw.equals(runtimeBlock) && !raw.equals(currentRequestBlock))
                .forEach(sections::add);

        String historyTranscript = ContextMessageFormatter.serializeHistoryTranscript(context.historyMessages()).strip();
        if (!historyTranscript.isBlank()) {
            sections.add("""
                    <history_transcript>
                    %s
                    </history_transcript>
                    """.formatted(historyTranscript).strip());
        }
        appendSectionIfPresent(sections, currentRequestBlock.isBlank() ? context.userPrompt().strip() : currentRequestBlock);
        return String.join("\n\n", sections);
    }

    @Nullable
    private String extractRawTaggedContext(Message message) {
        return switch (message) {
            case AssistantMessage assistantMessage -> {
                var taggedBlock = ContextMessageFormatter.parseTaggedBlock(assistantMessage.getText());
                yield taggedBlock != null ? taggedBlock.rawText() : null;
            }
            case UserMessage userMessage -> ContextMessageFormatter.parseTaggedBlocks(userMessage.getText()).stream()
                    .map(ContextMessageFormatter.TaggedBlock::rawText)
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse(null);
            default -> null;
        };
    }

    @Nullable
    private String findTaggedBlock(List<ContextMessageFormatter.TaggedBlock> blocks, String tagName) {
        return blocks.stream()
                .filter(block -> tagName.equals(block.tagName()))
                .map(ContextMessageFormatter.TaggedBlock::rawText)
                .findFirst()
                .orElse("");
    }

    private void appendSectionIfPresent(List<String> sections, @Nullable String text) {
        if (text != null && !text.isBlank()) {
            sections.add(text);
        }
    }

    @Nullable
    private Message toMessage(ReactStep step) {
        return switch (step) {
            case ReactStep.Progress ignored -> null;
            case ReactStep.Thought thought -> new AssistantMessage(thought.content());
            case ReactStep.ToolCall toolCall -> AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            toolCall.toolId(),
                            "function",
                            toolCall.toolId(),
                            toolCall.inputJson()
                    )))
                    .build();
            case ReactStep.Observation observation -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            observation.toolId(),
                            observation.toolId(),
                            observation.output()
                    )))
                    .build();
            case ReactStep.Answer answer -> new AssistantMessage(answer.content());
            case ReactStep.Suspend suspend -> new AssistantMessage(
                    "Agent 已挂起，等待恢复信号。挂起原因: " + formatSuspendReason(suspend.reason()));
            case ReactStep.Resume resume -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "resume:" + resume.payload().getClass().getSimpleName(),
                            "resume:" + resume.payload().getClass().getSimpleName(),
                            "Agent 已从挂起态恢复，挂起时长: " + resume.suspendDuration()
                                    + "，恢复载荷: " + resume.payload()
                    )))
                    .build();
        };
    }

    private void appendSection(StringBuilder buffer, String title, @Nullable String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        buffer.append('[')
                .append(title)
                .append("]\n")
                .append(content)
                .append("\n\n");
    }

    private String formatSuspendReason(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait workflowWait ->
                    "等待工作流完成: " + workflowWait.workflowName();
            case SuspendReason.UserConfirmation confirmation ->
                    "等待用户确认: " + confirmation.toolId();
            case SuspendReason.RemoteDelegation remoteDelegation ->
                    "等待远程代理返回: " + remoteDelegation.delegatedGoal();
            case SuspendReason.ScheduledWakeup scheduledWakeup ->
                    "等待定时唤醒: " + scheduledWakeup.reason();
            case SuspendReason.ExternalDataWait externalDataWait ->
                    "等待外部数据: " + externalDataWait.description();
        };
    }
}
