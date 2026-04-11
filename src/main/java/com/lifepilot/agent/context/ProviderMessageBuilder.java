package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ProviderMessageBuilder.class);
    private static final int CURRENT_TURN_DIGEST_MIN_CHARS = 320;

    private final TranscriptHygieneEngine hygieneEngine;
    private final SessionPruningEngine pruningEngine;

    public record BuildResult(
            List<Message> messages,
            TranscriptHygieneEngine.HygieneReport hygieneReport
    ) {
        public BuildResult {
            messages = List.copyOf(messages);
        }
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine) {
        this(hygieneEngine, new SessionPruningEngine(new com.lifepilot.agent.config.AgentConfigProperties(), new ObjectMapper()));
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine,
                                  SessionPruningEngine pruningEngine) {
        this.hygieneEngine = Objects.requireNonNull(hygieneEngine);
        this.pruningEngine = Objects.requireNonNull(pruningEngine);
    }

    public BuildResult build(AssembledContext context, ReactAgentState state) {
        List<Message> rawMessages = new ArrayList<>();
        rawMessages.add(new SystemMessage(context.systemPrompt()));
        rawMessages.add(buildUserMessage(buildStructuredPrompt(context), context.mediaContents()));

        convertStepsToMessages(state.steps(), rawMessages);

        TranscriptHygieneEngine.HygieneResult hygieneResult = hygieneEngine.clean(rawMessages);
        return new BuildResult(hygieneResult.messages(), hygieneResult.report());
    }

    /**
     * 将 ReactStep 列表转换为 LLM 消息列表，连续的 ToolCall 步骤合并为单条 AssistantMessage。
     *
     * <p>provider（如 DeepSeek）要求同一轮 tool_calls 必须在一条 AssistantMessage 中，
     * 且紧跟对应数量的 ToolResponseMessage。逐个 ToolCall 生成独立 AssistantMessage
     * 会导致消息序列校验失败。</p>
     */
    private void convertStepsToMessages(List<ReactStep> steps, List<Message> out) {
        var pendingToolCalls = new ArrayList<AssistantMessage.ToolCall>();

        for (ReactStep step : steps) {
            if (step instanceof ReactStep.ToolCall tc) {
                pendingToolCalls.add(new AssistantMessage.ToolCall(
                        tc.callId() != null ? tc.callId() : tc.toolId(),
                        "function",
                        sanitizeToolName(tc.toolId()),
                        tc.inputJson()
                ));
                continue;
            }

            // 遇到非 ToolCall 步骤时，先刷出累积的 ToolCall 批次
            flushPendingToolCalls(pendingToolCalls, out);

            Message message = toMessage(step);
            if (message != null) {
                out.add(message);
            }
        }

        // 尾部可能还有未刷出的 ToolCall
        flushPendingToolCalls(pendingToolCalls, out);
    }

    /** 将累积的 ToolCall 合并为单条 AssistantMessage 并清空缓冲区。 */
    private void flushPendingToolCalls(List<AssistantMessage.ToolCall> pending, List<Message> out) {
        if (pending.isEmpty()) return;
        // 检查 flush 后的消息序列：紧接的消息应为 ToolResponseMessage，否则 provider 可能拒绝
        if (!out.isEmpty() && !(out.getLast() instanceof ToolResponseMessage)) {
            // 合法路径：首次 flush（前面是 UserMessage/SystemMessage）或连续 flush
            // 但如果上一条是带 tool_calls 的 AssistantMessage 且没有对应 ToolResponseMessage，记录警告
            if (out.getLast() instanceof AssistantMessage am && am.hasToolCalls()) {
                log.warn("检测到连续 ToolCall flush：前一条 AssistantMessage 有 {} 个 tool_calls 但缺少对应的 ToolResponseMessage，"
                        + "provider 可能拒绝此消息序列", am.getToolCalls().size());
            }
        }
        out.add(buildAssistantToolCallMessage(List.copyOf(pending)));
        pending.clear();
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
            // ToolCall 由 convertStepsToMessages 批量合并处理，不在此单独转换
            case ReactStep.ToolCall ignored -> null;
            case ReactStep.Observation observation -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            observation.callId() != null ? observation.callId() : observation.toolId(),
                            sanitizeToolName(observation.toolId()),
                            formatObservationForPrompt(observation)
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
            // Reflect 不进入 LLM 消息列表 — 反思内容通过 L1 工作区 TASK_STATE 在下一轮
            // assemble 时注入上下文，避免额外 AssistantMessage 引发 provider 消息序列校验失败。
            case ReactStep.Reflect reflect -> {
                log.debug("Reflect 步骤已跳过消息转换（通过 workspace 注入）: trigger={}", reflect.trigger());
                yield null;
            }
        };
    }

    private String formatObservationForPrompt(ReactStep.Observation observation) {
        if (!shouldUseObservationPreview(observation)) {
            return observation.output();
        }
        String preview = pruningEngine.formatCurrentObservationPreview(
                observation.toolId(),
                observation.success(),
                observation.output()
        );
        return preview.isBlank() ? observation.output() : preview;
    }

    private boolean shouldUseObservationPreview(ReactStep.Observation observation) {
        if (observation.output() == null || observation.output().length() < CURRENT_TURN_DIGEST_MIN_CHARS) {
            return false;
        }
        return switch (observation.toolId()) {
            case "web.search", "web.fetch" -> true;
            default -> false;
        };
    }

    private AssistantMessage buildAssistantToolCallMessage(List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder()
                .toolCalls(toolCalls)
                .build();
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

    /** 将工具 ID 中的非法字符替换为下划线，满足 OpenAI API 名称模式 ^[a-zA-Z0-9_-]+$。 */
    private static String sanitizeToolName(String toolId) {
        if (toolId == null || toolId.isBlank()) return "tool";
        return toolId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
