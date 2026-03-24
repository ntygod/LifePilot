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
 * ProviderMessageBuilder 负责把 Agent 上下文和 ReAct 步骤构造成 provider 消息序列。
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
        rawMessages.add(buildUserMessage(context.userPrompt(), context.mediaContents()));

        for (ReactStep step : state.steps()) {
            Message message = toMessage(step);
            if (message != null) {
                rawMessages.add(message);
            }
        }

        TranscriptHygieneEngine.HygieneResult hygieneResult = hygieneEngine.clean(rawMessages);
        return new BuildResult(hygieneResult.messages(), hygieneResult.report());
    }

    public String serializeForMultimodal(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        boolean hasToolHistory = messages.stream().anyMatch(message -> message instanceof ToolResponseMessage);
        if (!hasToolHistory) {
            return messages.stream()
                    .filter(message -> message instanceof UserMessage)
                    .map(message -> ((UserMessage) message).getText())
                    .findFirst()
                    .orElse("");
        }

        StringBuilder buffer = new StringBuilder();
        for (Message message : messages) {
            switch (message) {
                case SystemMessage systemMessage -> appendSection(buffer, "系统指令", systemMessage.getText());
                case UserMessage userMessage -> appendSection(buffer, "用户消息", userMessage.getText());
                case AssistantMessage assistantMessage -> {
                    if (assistantMessage.hasToolCalls()) {
                        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                            buffer.append("[工具调用] ")
                                    .append(toolCall.name())
                                    .append("\n参数: ")
                                    .append(toolCall.arguments())
                                    .append("\n\n");
                        }
                    }
                    if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                        appendSection(buffer, "助手思考", assistantMessage.getText());
                    }
                }
                case ToolResponseMessage toolResponseMessage -> {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        buffer.append("[工具结果] ")
                                .append(response.name())
                                .append("\n")
                                .append(response.responseData())
                                .append("\n\n");
                    }
                }
                default -> {
                    // 忽略其他消息类型
                }
            }
        }
        return buffer.toString().strip();
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

    @Nullable
    private Message toMessage(ReactStep step) {
        return switch (step) {
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
        buffer.append('[').append(title).append("]\n")
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
