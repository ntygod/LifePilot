package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.thinking.ReasoningContentMarker;
import com.lifepilot.agent.learning.experience.ToolTipResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
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
 * <p>推理模型多轮契约（DeepSeek V4 / Qwen3 等）要求带 tool_calls 的 assistant 消息
 * 必须回传上一轮的 reasoning_content；Spring AI 的 {@link AssistantMessage} 抽象
 * 不直接暴露 reasoning_content 字段，因此本类把 reasoning_content 编码进
 * AssistantMessage.text 的 sentinel marker 区段（{@link ReasoningContentMarker}），
 * 由 {@link com.lifepilot.llm.thinking.ReasoningContentInjectionRewriter} 在请求体
 * 出去前抽出 marker 内容、注入到 OpenAI 协议字段、并清理 content。
 * marker 仅在 ProviderMessageBuilder → Spring AI ChatModel → 请求体改写 filter
 * 这条单链路里短暂存在，不污染 ReactStep / transcript / SSE / UI 等外部数据流。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class ProviderMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProviderMessageBuilder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TranscriptHygieneEngine hygieneEngine;
    private final SessionPruningEngine pruningEngine;
    @Nullable
    private final ToolTipResolver toolTipResolver;

    public record BuildResult(
            List<Message> messages,
            TranscriptHygieneEngine.HygieneReport hygieneReport
    ) {
        public BuildResult {
            messages = List.copyOf(messages);
        }
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine) {
        this(hygieneEngine, new SessionPruningEngine(new AgentConfigProperties(), new ObjectMapper()), null);
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine,
                                  SessionPruningEngine pruningEngine) {
        this(hygieneEngine, pruningEngine, null);
    }

    public ProviderMessageBuilder(TranscriptHygieneEngine hygieneEngine,
                                  SessionPruningEngine pruningEngine,
                                  @Nullable ToolTipResolver toolTipResolver) {
        this.hygieneEngine = Objects.requireNonNull(hygieneEngine);
        this.pruningEngine = Objects.requireNonNull(pruningEngine);
        this.toolTipResolver = toolTipResolver;
    }

    public BuildResult build(AssembledContext context, ReactAgentState state) {
        List<Message> rawMessages = new ArrayList<>();
        rawMessages.add(new SystemMessage(context.systemPrompt()));
        rawMessages.add(buildUserMessage(buildStructuredPrompt(context), context.mediaContents()));

        convertStepsToMessages(state.steps(), rawMessages, state.sessionId());

        TranscriptHygieneEngine.HygieneResult hygieneResult = hygieneEngine.clean(rawMessages);
        return new BuildResult(hygieneResult.messages(), hygieneResult.report());
    }

    /**
     * 将 ReactStep 列表转换为 LLM 消息列表，把同一次 LLM 调用产生的 {@code Thought + ToolCall}
     * 合并为单条 {@link AssistantMessage}（content + tool_calls 共存）。
     *
     * <p>合并语义对齐 OpenAI 协议：一次 LLM 响应对应一条 assistant message，content 是
     * 模型的可见文本输出（含 ReAct Thought），tool_calls 是工具调用请求。两者来自同一次
     * 推理调用，理应同属一条 message。早期实现把它们拆成两条独立 AssistantMessage，
     * 在 DeepSeek thinking 模式下触发 400 — DeepSeek 严格要求 user 消息后任何 assistant
     * 都需 reasoning_content；拆出来的 content-only Thought 缺 reasoning_content 字段被拒。</p>
     *
     * <p>同一组并行 ToolCall step 共享一段 reasoning_content（来自单次 LLM 响应），
     * 合并时把 reasoning_content + thoughtText 编码到 AssistantMessage 的 content
     * marker 段，由请求体改写 filter 在请求出去前抽出 marker 注入 OpenAI 协议字段、
     * 并清理 marker 还原 content 为 thoughtText。</p>
     */
    private void convertStepsToMessages(List<ReactStep> steps,
                                        List<Message> out,
                                        @Nullable String sessionId) {
        var pendingToolCalls = new ArrayList<AssistantMessage.ToolCall>();
        String pendingThoughtText = null;
        String pendingReasoning = null;

        for (ReactStep step : steps) {
            if (step instanceof ReactStep.Thought thought) {
                // Thought 暂存，等待紧随的 ToolCall 一起合并；理论上 Thought 后必跟 ToolCall
                // （ReactAgentLoop 仅在 hasToolCalls 时生成 Thought step）
                if (pendingThoughtText != null || !pendingToolCalls.isEmpty()) {
                    // 不变量被破坏：连续 Thought 或 Thought 在 ToolCall 后 — 先 flush 当前 pending
                    log.warn("ReactStep 序列异常: 连续 Thought 或 ToolCall 后接 Thought; pendingThought={}, pendingToolCalls={}",
                            pendingThoughtText != null, pendingToolCalls.size());
                    flushPendingAssistant(pendingThoughtText, pendingToolCalls, pendingReasoning, out);
                    pendingThoughtText = null;
                    pendingToolCalls.clear();
                    pendingReasoning = null;
                }
                pendingThoughtText = thought.content();
                continue;
            }
            if (step instanceof ReactStep.ToolCall tc) {
                pendingToolCalls.add(new AssistantMessage.ToolCall(
                        tc.callId() != null ? tc.callId() : tc.toolId(),
                        "function",
                        sanitizeToolName(tc.toolId()),
                        tc.inputJson()
                ));
                // 同一组并行 tool call 共享一段 reasoning_content；首个非空值生效
                if (pendingReasoning == null && tc.reasoningContent() != null) {
                    pendingReasoning = tc.reasoningContent();
                }
                continue;
            }

            // 遇到非 Thought / 非 ToolCall 步骤时，先 flush 累积的 assistant message
            flushPendingAssistant(pendingThoughtText, pendingToolCalls, pendingReasoning, out);
            pendingThoughtText = null;
            pendingReasoning = null;

            Message message = toMessage(step, sessionId);
            if (message != null) {
                out.add(message);
            }
        }

        // 尾部可能还有未刷出的 assistant pending
        flushPendingAssistant(pendingThoughtText, pendingToolCalls, pendingReasoning, out);
    }

    /**
     * 将累积的 Thought 文本 + 并行 ToolCall + 共享 reasoning_content 合并为单条
     * {@link AssistantMessage}（content + tool_calls）并清空缓冲区。
     *
     * @param thoughtText 本次 LLM 调用的可见文本输出（content）；可空
     * @param pending     本次 LLM 调用的并行 tool_calls 列表
     * @param reasoning   本次 LLM 调用的 reasoning_content；非空时编码到 content marker
     */
    private void flushPendingAssistant(@Nullable String thoughtText,
                                       List<AssistantMessage.ToolCall> pending,
                                       @Nullable String reasoning,
                                       List<Message> out) {
        boolean hasThought = thoughtText != null && !thoughtText.isEmpty();
        boolean hasToolCalls = !pending.isEmpty();
        if (!hasThought && !hasToolCalls) {
            return;
        }

        if (hasToolCalls) {
            // 检查 flush 后的消息序列：紧接的消息应为 ToolResponseMessage
            if (!out.isEmpty() && !(out.getLast() instanceof ToolResponseMessage)) {
                if (out.getLast() instanceof AssistantMessage am && am.hasToolCalls()) {
                    log.warn("检测到连续 ToolCall flush：前一条 AssistantMessage 有 {} 个 tool_calls 但缺少对应的 ToolResponseMessage，"
                            + "provider 可能拒绝此消息序列", am.getToolCalls().size());
                }
            }
            out.add(buildAssistantToolCallMessage(List.copyOf(pending), thoughtText, reasoning));
            pending.clear();
        } else {
            // 仅 Thought 没 ToolCall — 罕见路径（ReactAgentLoop 仅 hasToolCalls 时生成 Thought）
            out.add(new AssistantMessage(thoughtText));
        }
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
        // 多模态 / 调试序列化路径不需感知 reasoning_content marker；统一剥离避免污染下游消费方
        String rawText = ReasoningContentMarker.stripMarker(assistantMessage.getText());
        ContextMessageFormatter.TaggedBlock taggedBlock =
                ContextMessageFormatter.parseTaggedBlock(rawText);
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

        if (rawText != null && !rawText.isBlank()) {
            appendSection(buffer, "assistant", rawText);
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
        String loadedSkillsBlock = findTaggedBlock(promptBlocks, "loaded_skills");
        String runtimeBlock = findTaggedBlock(promptBlocks, "runtime_context");
        String currentRequestBlock = findTaggedBlock(promptBlocks, "current_request");

        List<String> sections = new ArrayList<>();
        appendSectionIfPresent(sections, loadedSkillsBlock);
        appendSectionIfPresent(sections, runtimeBlock);
        context.contextMessages().stream()
                .map(this::extractRawTaggedContext)
                .filter(Objects::nonNull)
                .forEach(sections::add);
        promptBlocks.stream()
                .map(ContextMessageFormatter.TaggedBlock::rawText)
                .filter(raw -> !raw.equals(loadedSkillsBlock)
                        && !raw.equals(runtimeBlock)
                        && !raw.equals(currentRequestBlock))
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
    private Message toMessage(ReactStep step, @Nullable String sessionId) {
        return switch (step) {
            case ReactStep.Progress ignored -> null;
            case ReactStep.Thought thought -> new AssistantMessage(thought.content());
            // ToolCall 由 convertStepsToMessages 批量合并处理，不在此单独转换
            case ReactStep.ToolCall ignored -> null;
            case ReactStep.Observation observation -> {
                // Skill 指南已提升到系统提示词，对话历史中用摘要替代原文避免重复
                String content = isPromotedSkillResult(observation)
                        ? buildSkillLoadSummary(observation.output())
                        : formatObservationForPrompt(observation, sessionId);
                yield ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                observation.callId() != null ? observation.callId() : observation.toolId(),
                                sanitizeToolName(observation.toolId()),
                                content
                        )))
                        .build();
            }
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

    /**
     * 判断 Observation 是否为已提升到系统提示词的 Skill 加载结果。
     *
     * <p>{@code skill.load} 工具的成功输出会被 {@link com.lifepilot.agent.execution.ToolExecutionCoordinator}
     * 合并到 {@code state.loadedSkillContent}，在下一轮 {@code ContextAssembler} 组装时重新注入系统提示词；
     * 对话历史里只需保留一行摘要即可，避免 SKILL.md 原文在 Observation 里重复占用上下文。</p>
     */
    private boolean isPromotedSkillResult(ReactStep.Observation observation) {
        return observation.success()
                && "skill.load".equals(observation.toolId())
                && observation.output() != null
                && !observation.output().isBlank();
    }

    /** 从 skill.load 结果中提取 content XML，生成简要摘要。 */
    private String buildSkillLoadSummary(String output) {
        try {
            var data = OBJECT_MAPPER.readTree(output);
            var content = data.path("content").asText("");
            // content 形如 <skill name="x">...</skill>\n\n<skill name="y">...</skill>
            var names = new java.util.ArrayList<String>();
            var matcher = java.util.regex.Pattern.compile("<skill\\s+name=\"([^\"]+)\"")
                    .matcher(content);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            if (!names.isEmpty()) {
                return "已加载 Skill 指南: " + String.join(", ", names);
            }
        } catch (Exception ignored) {}
        return "Skill 指南已加载";
    }

    private String formatObservationForPrompt(ReactStep.Observation observation,
                                              @Nullable String sessionId) {
        String body;
        if (shouldUseObservationPreview(observation)) {
            String preview = pruningEngine.formatCurrentObservationPreview(
                    observation.toolId(),
                    observation.success(),
                    observation.output()
            );
            body = preview.isBlank() ? observation.output() : preview;
        } else if (!observation.success()) {
            // 失败 ToolResult 截断 —— PowerShell CLIXML / Python traceback / 各种工具
            // 错误输出的尾部经常是低信息量的状态字段或 XML 噪音，前 600 字一般足够
            // LLM 理解错误根因。截断后单条工具失败 result 在多轮 history 里占用 token
            // 从动辄 1-3KB 降到 ~600 字，避免 ReAct 多轮工具失败把 prompt 撑爆。
            body = truncateFailureOutput(observation.output());
        } else {
            body = observation.output();
        }
        // 防御：Observation.output 合约上非 null，但上游异常场景（工具抛异常且被吞、
        // 序列化反序列化边界）仍可能为 null。显式兜底为空串，避免字符串拼接产出 "null" 字面量。
        if (body == null) {
            body = "";
        }
        // 工具级经验提示由呈现层动态拼接，保持 Observation.output 自身为纯净 JSON。
        if (toolTipResolver != null) {
            String tips = toolTipResolver.tipsFor(observation.toolId(), sessionId);
            if (tips != null && !tips.isEmpty()) {
                return tips + "\n" + body;
            }
        }
        return body;
    }

    /** 失败工具输出截断长度上限（前 N 字，超出追加 ...[已截断] 提示）。 */
    private static final int FAILURE_OUTPUT_MAX_CHARS = 600;

    /**
     * 截断失败工具输出 —— 保留前 {@value #FAILURE_OUTPUT_MAX_CHARS} 字，超出截断尾部。
     *
     * <p>失败 ToolResult 的关键信息（错误类型、首行栈、关键字段）通常在前几百字内；
     * 后面的 PowerShell CLIXML 进度对象 / Python traceback 中段 / 各种 metadata 噪音
     * 对 LLM 理解错误意义不大，但累计在多轮 ReAct 里能占用大量 prompt token。</p>
     */
    static String truncateFailureOutput(String output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        if (output.length() <= FAILURE_OUTPUT_MAX_CHARS) {
            return output;
        }
        return output.substring(0, FAILURE_OUTPUT_MAX_CHARS)
                + "\n...[失败输出已截断, 总长度=" + output.length() + " 字]";
    }

    /**
     * 当回合 Observation 是否走结构化摘要（240 字 preview）。
     *
     * <p>白名单仅保留 {@code web.search} —— 它的输出本身就是结构化短摘要
     * （query/answer/title/url），preview 进一步提取要点，LLM 能据此决定下一步抓取哪个 URL。
     * 历史上 {@code web.fetch} 也走 preview，但会让 LLM 误以为正文没拿全反复重复调用，
     * 因此 {@code web.fetch} 当回合改走完整 output（一次取到正文做总结，不再回拉）。</p>
     */
    private boolean shouldUseObservationPreview(ReactStep.Observation observation) {
        return "web.search".equals(observation.toolId());
    }

    private AssistantMessage buildAssistantToolCallMessage(List<AssistantMessage.ToolCall> toolCalls,
                                                           @Nullable String thoughtText,
                                                           @Nullable String reasoningContent) {
        var builder = AssistantMessage.builder().toolCalls(toolCalls);
        // content 编码：marker(reasoning) + thoughtText；filter 在请求出去前抽 marker 注入
        // reasoning_content 字段、清理 marker 还原 content 为 thoughtText（或空）
        // reasoningContent != null（包括 ""）都编码 marker —— 空 reasoning 表示"thinking
        // 模式但本次思考为空"，DeepSeek 多轮契约仍需回传字段。null 表示"非 thinking 模式"
        // 不编码 marker（如 GPT-4o 普通响应）。
        boolean hasReasoning = reasoningContent != null;
        boolean hasThought = thoughtText != null && !thoughtText.isEmpty();
        if (hasReasoning) {
            builder.content(ReasoningContentMarker.encode(hasThought ? thoughtText : null, reasoningContent));
        } else if (hasThought) {
            builder.content(thoughtText);
        }
        return builder.build();
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
            case SuspendReason.BrowserTakeover browserTakeover ->
                    "等待浏览器人工接管: " + browserTakeover.reason();
        };
    }

    /** 将工具 ID 中的非法字符替换为下划线，满足 OpenAI API 名称模式 ^[a-zA-Z0-9_-]+$。 */
    private static String sanitizeToolName(String toolId) {
        if (toolId == null || toolId.isBlank()) return "tool";
        return toolId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
