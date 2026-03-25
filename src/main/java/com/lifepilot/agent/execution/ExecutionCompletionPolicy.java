package com.lifepilot.agent.execution;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行链路完成判定策略。
 *
 * <p>负责识别显式完成/阻塞协议、等待用户补充信息协议，
 * 以及对阶段性说明的提前结束拦截。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public final class ExecutionCompletionPolicy {

    private static final Pattern AWAIT_USER_INPUT_PATTERN = Pattern.compile(
            "<await_user_input>\\s*(.*?)\\s*</await_user_input>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern COMPLETION_CONTROL_PATTERN = Pattern.compile(
            "<completion_control>\\s*(done|blocked|continue)\\s*</completion_control>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * 评估当前这段“无工具调用文本”应如何收尾。
     *
     * <p>判定顺序是：先识别显式终态，再识别“等待用户补充信息”，
     * 然后拦截疑似提前结束，最后才允许按普通直接回答结束。</p>
     */
    public CompletionEvaluation evaluate(AgentRequest request,
                                         ReactAgentState state,
                                         @Nullable String content) {
        var awaitUserInput = extractAwaitUserInput(content);
        if (awaitUserInput != null) {
            return new CompletionEvaluation(
                    CompletionDisposition.SUSPEND_FOR_USER_INPUT,
                    CompletionReason.SUSPENDED,
                    "await_user_input",
                    awaitUserInput.visibleContent(),
                    awaitUserInput.prompt()
            );
        }

        var completionControl = extractCompletionControl(content);
        if (completionControl != null) {
            return switch (completionControl.directive()) {
                case DONE -> new CompletionEvaluation(
                        CompletionDisposition.EXPLICIT_TERMINAL,
                        CompletionReason.EXPLICIT_COMPLETED,
                        null,
                        completionControl.visibleContent(),
                        null
                );
                case BLOCKED -> new CompletionEvaluation(
                        CompletionDisposition.EXPLICIT_TERMINAL,
                        CompletionReason.EXPLICIT_BLOCKED,
                        extractBlockedReason(completionControl.visibleContent()),
                        completionControl.visibleContent(),
                        null
                );
                case CONTINUE -> new CompletionEvaluation(
                        CompletionDisposition.REJECT_IMPLICIT_TERMINATION,
                        null,
                        null,
                        completionControl.visibleContent(),
                        null
                );
            };
        }

        var explicitDecision = classifyExplicitTerminal(content);
        if (explicitDecision != ExplicitDecision.CONTINUE) {
            CompletionReason completionReason = explicitDecision == ExplicitDecision.COMPLETED
                    ? CompletionReason.EXPLICIT_COMPLETED
                    : CompletionReason.EXPLICIT_BLOCKED;
            String terminationReason = explicitDecision == ExplicitDecision.BLOCKED
                    ? extractBlockedReason(content)
                    : null;
            return new CompletionEvaluation(
                    CompletionDisposition.EXPLICIT_TERMINAL,
                    completionReason,
                    terminationReason,
                    content,
                    null
            );
        }

        if (shouldRejectImplicitTermination(request, state, content)) {
            return new CompletionEvaluation(
                    CompletionDisposition.REJECT_IMPLICIT_TERMINATION,
                    null,
                    null,
                    content,
                    null
            );
        }

        return new CompletionEvaluation(
                CompletionDisposition.DIRECT_ANSWER,
                CompletionReason.DIRECT_ANSWER,
                null,
                content,
                null
        );
    }

    /** 识别模型是否显式声明了“已完成”或“已阻塞”。 */
    private ExplicitDecision classifyExplicitTerminal(@Nullable String content) {
        if (startsWithAnyPrefix(content, "已完成：", "已完成:", "completed:", "completed：")) {
            return ExplicitDecision.COMPLETED;
        }
        if (startsWithAnyPrefix(content, "已阻塞：", "已阻塞:", "blocked:", "blocked：")) {
            return ExplicitDecision.BLOCKED;
        }
        return ExplicitDecision.CONTINUE;
    }

    /**
     * 提取“等待用户补充信息”协议。
     *
     * <p>标签内文本是面向用户展示的自然语言追问，运行时会在内部挂起，
     * 但展示时去掉标签本身。</p>
     */
    @Nullable
    private AwaitUserInput extractAwaitUserInput(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = AWAIT_USER_INPUT_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String prompt = matcher.group(1) != null ? matcher.group(1).strip() : "";
        if (prompt.isBlank()) {
            return null;
        }
        String visibleContent = matcher.replaceAll(matchResult ->
                matchResult.group(1) != null ? matchResult.group(1).strip() : "")
                .strip();
        if (visibleContent.isBlank()) {
            visibleContent = prompt;
        }
        return new AwaitUserInput(visibleContent, prompt);
    }

    /**
     * 判断当前文本是否更像“阶段性说明”，因此不能被当成最终答案。
     *
     * <p>当本轮已经进入真实执行阶段后，无工具调用的文本回复必须带结构化 completion tag。
     * 如果没有 tag，就视为协议未满足，而不是再去硬编码猜测“像不像某句话”。</p>
     */
    private boolean shouldRejectImplicitTermination(AgentRequest request,
                                                    ReactAgentState state,
                                                    @Nullable String content) {
        String normalized = normalizeTerminationText(content);
        if (normalized.isBlank()) {
            return false;
        }
        return state.taskMode() == AgentTaskMode.EXECUTION || hasSuspendResumeEvidence(state);
    }

    /** 检查当前 state 是否已经进入过“挂起/恢复”这类必须接续原任务的执行阶段。 */
    private boolean hasSuspendResumeEvidence(ReactAgentState state) {
        return state.steps().stream().anyMatch(step ->
                step instanceof ReactStep.Suspend
                        || step instanceof ReactStep.Resume);
    }

    /**
     * 提取隐藏 completion control tag。
     *
     * <p>这类 tag 只用于运行时判断，不应该向用户展示。
     * 模型可以先输出自然语言，再在末尾追加 tag。</p>
     */
    @Nullable
    private CompletionControl extractCompletionControl(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = COMPLETION_CONTROL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String rawDirective = matcher.group(1);
        if (rawDirective == null || rawDirective.isBlank()) {
            return null;
        }
        CompletionDirective directive = CompletionDirective.valueOf(rawDirective.strip().toUpperCase(Locale.ROOT));
        String visibleContent = matcher.replaceAll("").strip();
        return new CompletionControl(directive, visibleContent);
    }

    /** 对大小写和全半角冒号做宽松前缀匹配。 */
    private boolean startsWithAnyPrefix(@Nullable String content, String... prefixes) {
        if (content == null) {
            return false;
        }
        String trimmed = content.strip();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lowered.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 规范化文本，便于后续规则匹配。 */
    private String normalizeTerminationText(@Nullable String content) {
        if (content == null) {
            return "";
        }
        return content.strip()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .toLowerCase(Locale.ROOT);
    }

    /** 从“已阻塞”文本中提取给用户看的阻塞原因。 */
    private String extractBlockedReason(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return "任务已阻塞";
        }
        String trimmed = content.strip();
        for (String prefix : List.of("已阻塞：", "已阻塞:", "BLOCKED:", "BLOCKED：", "blocked:", "blocked：")) {
            if (trimmed.startsWith(prefix)) {
                String reason = trimmed.substring(prefix.length()).strip();
                return reason.isEmpty() ? "任务已阻塞" : reason;
            }
        }
        return trimmed;
    }

    /** completion_control 的结构化指令。 */
    private enum CompletionDirective {
        DONE,
        BLOCKED,
        CONTINUE
    }

    /** 显式终态的粗粒度分类。 */
    private enum ExplicitDecision {
        CONTINUE,
        COMPLETED,
        BLOCKED
    }

    /** completion policy 给主循环的决策结果。 */
    public enum CompletionDisposition {
        EXPLICIT_TERMINAL,
        SUSPEND_FOR_USER_INPUT,
        REJECT_IMPLICIT_TERMINATION,
        DIRECT_ANSWER
    }

    /** 主循环消费的判定结果载体。 */
    public record CompletionEvaluation(
            CompletionDisposition disposition,
            @Nullable CompletionReason completionReason,
            @Nullable String terminationReason,
            @Nullable String userVisibleContent,
            @Nullable String suspendPrompt
    ) {
    }

    /** await_user_input 协议解析结果。 */
    private record AwaitUserInput(
            String visibleContent,
            String prompt
    ) {
    }

    /** completion_control 协议解析结果。 */
    private record CompletionControl(
            CompletionDirective directive,
            String visibleContent
    ) {
    }
}
