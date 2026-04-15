package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认提醒文案生成器。
 *
 * <p>优先使用 LLM 生成更自然的提醒文案，调用不可用时自动回退到模板文案。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class DefaultReminderMessageGenerator implements ReminderMessageGenerator {

    private static final Logger log = LoggerFactory.getLogger(DefaultReminderMessageGenerator.class);
    private static final String PROMPT_KEY = "generation/proactive-reminder";
    private static final int MAX_EVIDENCE_LINES = 4;
    private static final int MAX_BODY_CHARS = 120;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;
    private final AgentConfigProperties config;

    public DefaultReminderMessageGenerator(@Nullable GenerationRouter generationRouter,
                                           @Nullable PromptRegistry promptRegistry,
                                           AgentConfigProperties config) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = Objects.requireNonNull(config, "config 不能为空");
    }

    @Override
    public ReminderMessage generate(String userId,
                                    ReminderDecision decision,
                                    ReminderTopicSnapshot snapshot,
                                    ReminderRuntimeContext context) {
        if (generationRouter == null || promptRegistry == null) {
            return buildFallback(decision, snapshot, context);
        }
        try {
            String prompt = promptRegistry.render(PROMPT_KEY, buildPromptVariables(decision, snapshot, context));
            // skipCache=true：每个 topic 的 prompt 模板相似但内容不同，
            // 语义缓存会误命中导致不同 topic 返回相同文案
            LlmResponse response = generationRouter.call(
                    resolveScene(),
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    Duration.ofSeconds(Math.max(3, config.getTask().getProactiveReminderLlmTimeoutSeconds())),
                    true
            );
            String body = sanitize(response.content());
            if (body.isBlank()) {
                log.debug("主动提醒文案生成返回空结果，回退模板文案: userId={}, topicKey={}",
                        userId, decision.candidate().topicKey());
                return buildFallback(decision, snapshot, context);
            }
            return new ReminderMessage(body, "llm", response.providerId(), response.modelName());
        } catch (Exception e) {
            log.debug("主动提醒文案生成失败，回退模板文案: userId={}, topicKey={}, errorType={}, error={}",
                    userId,
                    decision.candidate().topicKey(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return buildFallback(decision, snapshot, context);
        }
    }

    private Map<String, Object> buildPromptVariables(ReminderDecision decision,
                                                     ReminderTopicSnapshot snapshot,
                                                     ReminderRuntimeContext context) {
        ReminderCandidate candidate = decision.candidate();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("actionLabel", actionLabel(decision.action()));
        variables.put("topicTitle", candidate.title());
        variables.put("candidateType", candidateTypeLabel(candidate.type()));
        variables.put("currentTime", formatInstant(context.now(), context.zoneId()));
        variables.put("suggestedAt", formatInstant(candidate.suggestedAt(), context.zoneId()));
        variables.put("decisionReason", defaultIfBlank(decision.reason(), "当前时机较合适"));
        variables.put("candidateRationale", defaultIfBlank(candidate.rationale(), "暂无额外候选理由"));
        variables.put("evidenceLines", formatEvidenceLines(snapshot, context.zoneId()));
        variables.put("historySummary", formatHistorySummary(snapshot, context.zoneId()));
        return variables;
    }

    private ReminderMessage buildFallback(ReminderDecision decision,
                                          ReminderTopicSnapshot snapshot,
                                          ReminderRuntimeContext context) {
        ReminderCandidate candidate = decision.candidate();
        String lead = switch (decision.action()) {
            case SOFT_PUSH -> "你最近在留意「%s」".formatted(candidate.title());
            case NORMAL_PUSH -> "关于「%s」，现在可能是个合适的处理时间".formatted(candidate.title());
            default -> "关于「%s」".formatted(candidate.title());
        };
        String reason = fallbackReason(decision, snapshot, context.zoneId());
        String close = fallbackClose(candidate.type(), decision.action());
        String body = sanitize(joinSentences(lead, reason, close));
        if (body.isBlank()) {
            body = "关于「%s」，你前面提过这件事，现在值得看一眼。".formatted(candidate.title());
        }
        return new ReminderMessage(body, "fallback", null, null);
    }

    private String fallbackReason(ReminderDecision decision,
                                  ReminderTopicSnapshot snapshot,
                                  ZoneId zoneId) {
        ReminderSignal primarySignal = findPrimarySignal(snapshot, decision.candidate().signalId());
        if (primarySignal != null && primarySignal.summary() != null && !primarySignal.summary().isBlank()) {
            return ensureSentence(primarySignal.summary());
        }
        if (decision.candidate().rationale() != null && !decision.candidate().rationale().isBlank()) {
            return ensureSentence(decision.candidate().rationale());
        }
        if (primarySignal != null && primarySignal.relevantAt() != null) {
            return ensureSentence("相关时间点在%s附近".formatted(formatInstant(primarySignal.relevantAt(), zoneId)));
        }
        if (decision.reason() != null && !decision.reason().isBlank()) {
            return ensureSentence(decision.reason());
        }
        return "";
    }

    private String fallbackClose(ReminderCandidateType candidateType, ReminderAction action) {
        return switch (candidateType) {
            case DUE_SOON, PREPARATION_WINDOW -> action == ReminderAction.SOFT_PUSH
                    ? "如果方便，现在顺手处理一下会更稳妥。"
                    : "现在处理会更从容一些。";
            case HABIT_WINDOW -> "如果你准备按平时节奏推进，现在可以开始。";
            case COMMITMENT_GAP -> "如果这件事还没收尾，现在补一下会更轻松。";
            case BEHAVIOR_ANOMALY -> "如果你本来打算继续推进，现在可以看一眼。";
        };
    }

    private ReminderSignal findPrimarySignal(ReminderTopicSnapshot snapshot, String signalId) {
        return snapshot.signals().stream()
                .filter(signal -> signal.signalId().equals(signalId))
                .findFirst()
                .orElse(snapshot.signals().stream().findFirst().orElse(null));
    }

    private String formatEvidenceLines(ReminderTopicSnapshot snapshot, ZoneId zoneId) {
        List<String> lines = new ArrayList<>();
        for (ReminderSignal signal : snapshot.signals().stream().limit(MAX_EVIDENCE_LINES).toList()) {
            List<String> parts = new ArrayList<>();
            parts.add(signalKindLabel(signal.kind()));
            if (signal.summary() != null && !signal.summary().isBlank()) {
                parts.add(signal.summary());
            }
            if (signal.relevantAt() != null) {
                parts.add("相关时间 %s".formatted(formatInstant(signal.relevantAt(), zoneId)));
            }
            if (signal.preferredWindowStartHour() != null && signal.preferredWindowEndHour() != null) {
                parts.add("偏好窗口 %02d:00-%02d:00".formatted(
                        signal.preferredWindowStartHour(),
                        signal.preferredWindowEndHour()));
            }
            lines.add("- " + String.join("；", parts));
        }
        return lines.isEmpty() ? "- 暂无额外证据" : String.join("\n", lines);
    }

    private String formatHistorySummary(ReminderTopicSnapshot snapshot, ZoneId zoneId) {
        ReminderTopicState state = snapshot.state();
        List<String> parts = new ArrayList<>();
        if (state.lastRemindedAt() != null) {
            parts.add("最近提醒 %s".formatted(formatInstant(state.lastRemindedAt(), zoneId)));
        }
        parts.add("近30天已处理 %d 次".formatted(state.actedCount30d()));
        parts.add("稍后 %d 次".formatted(state.snoozedCount30d()));
        parts.add("忽略 %d 次".formatted(state.dismissedCount30d()));
        parts.add("不相关 %d 次".formatted(state.notRelevantCount30d()));
        if (state.muted()) {
            parts.add("该主题已静默");
        }
        return String.join("；", parts);
    }

    private String sanitize(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r", "").trim();
        normalized = normalized.replaceAll("(?s)^```(?:\\w+)?\\s*", "");
        normalized = normalized.replaceAll("(?s)```$", "");
        normalized = stripWrappingQuotes(normalized.trim());

        List<String> lines = normalized.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(3)
                .toList();
        String merged = String.join("\n", lines).trim();
        if (merged.length() > MAX_BODY_CHARS) {
            merged = merged.substring(0, MAX_BODY_CHARS - 3).trim() + "...";
        }
        return merged;
    }

    private String stripWrappingQuotes(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        boolean wrapped = (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '“' && last == '”');
        return wrapped ? value.substring(1, value.length() - 1).trim() : value;
    }

    private String joinSentences(String... parts) {
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            normalized.add(ensureSentence(part));
        }
        return String.join("", normalized);
    }

    private String ensureSentence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？")
                || trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
            return trimmed;
        }
        return trimmed + "。";
    }

    private String formatInstant(@Nullable Instant instant, ZoneId zoneId) {
        if (instant == null) {
            return "未指定";
        }
        return DATE_TIME_FORMATTER.format(instant.atZone(zoneId));
    }

    private String actionLabel(ReminderAction action) {
        return switch (action) {
            case SOFT_PUSH -> "轻提醒";
            case NORMAL_PUSH -> "主动提醒";
            case PREPARE -> "预备执行";
            case AUTO_EXECUTE -> "自动执行";
            case DEFER_TO_WINDOW -> "延后提醒";
            case SKIP -> "跳过";
        };
    }

    private String candidateTypeLabel(ReminderCandidateType candidateType) {
        return switch (candidateType) {
            case DUE_SOON -> "截止临近";
            case COMMITMENT_GAP -> "承诺未闭环";
            case HABIT_WINDOW -> "习惯窗口";
            case PREPARATION_WINDOW -> "事件前准备";
            case BEHAVIOR_ANOMALY -> "行为异常";
        };
    }

    private String signalKindLabel(ReminderSignalKind signalKind) {
        return switch (signalKind) {
            case DEADLINE -> "截止";
            case COMMITMENT -> "承诺";
            case HABIT -> "习惯";
            case EVENT -> "事件";
            case ANOMALY -> "异常";
        };
    }

    private String defaultIfBlank(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveScene() {
        String configured = config.getTask().getProactiveReminderLlmScene();
        return configured == null || configured.isBlank() ? LlmScene.CHAT : configured;
    }
}
