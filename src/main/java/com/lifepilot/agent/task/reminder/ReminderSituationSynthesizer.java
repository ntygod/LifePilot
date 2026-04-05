package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 情境合成器 — 将多个待推送决策合成为情境化建议。
 *
 * <p>评分引擎决定「该不该说」，合成器决定「怎么说、说什么有价值的」。
 * 单条决策跳过合成直接投递，多条决策请求 LLM 判断关联性并生成合并文案，
 * LLM 不可用时回退为逐条投递。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class ReminderSituationSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(ReminderSituationSynthesizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_KEY = "generation/proactive-reminder-situation";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_DECISIONS_FOR_SYNTHESIS = 8;

    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;

    public ReminderSituationSynthesizer(@Nullable GenerationRouter generationRouter,
                                         @Nullable PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 合成情境。
     *
     * @param outcomes    本轮所有非 SKIP 的决策
     * @param topicIndex  主题快照索引
     * @param context     运行时上下文
     * @return 情境列表（每个情境可能包含一个或多个主题）
     */
    public List<ReminderSituation> synthesize(List<ReminderDecisionOutcome> outcomes,
                                               Map<String, ReminderTopicSnapshot> topicIndex,
                                               ReminderRuntimeContext context) {
        // 只对需要推送的决策做合成
        List<ReminderDecisionOutcome> pushable = outcomes.stream()
                .filter(o -> o.decision().action() == ReminderAction.SOFT_PUSH
                        || o.decision().action() == ReminderAction.NORMAL_PUSH
                        || o.decision().action() == ReminderAction.PREPARE
                        || o.decision().action() == ReminderAction.AUTO_EXECUTE)
                .toList();

        // 单条或无 → 跳过合成
        if (pushable.size() <= 1) {
            return pushable.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }

        // LLM 不可用 → 回退逐条
        if (generationRouter == null || promptRegistry == null) {
            log.debug("情境合成跳过: LLM 或 PromptRegistry 不可用，回退逐条投递");
            return pushable.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }

        // 截断过多的决策
        List<ReminderDecisionOutcome> toSynthesize = pushable.size() > MAX_DECISIONS_FOR_SYNTHESIS
                ? pushable.subList(0, MAX_DECISIONS_FOR_SYNTHESIS)
                : pushable;

        try {
            return callLlmSynthesis(toSynthesize, topicIndex, context);
        } catch (Exception e) {
            log.debug("情境合成 LLM 调用失败，回退逐条投递: {}", e.getMessage());
            return pushable.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }
    }

    private List<ReminderSituation> callLlmSynthesis(List<ReminderDecisionOutcome> outcomes,
                                                      Map<String, ReminderTopicSnapshot> topicIndex,
                                                      ReminderRuntimeContext context) {
        String currentTime = context.now().atZone(context.zoneId()).format(DATE_TIME_FORMATTER);
        String decisionItems = buildDecisionItemsText(outcomes, topicIndex);
        String recentContext = buildRecentContext(topicIndex);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("currentTime", currentTime);
        variables.put("decisionItems", decisionItems);
        variables.put("recentContext", recentContext);

        String prompt = promptRegistry.render(PROMPT_KEY, variables);
        LlmResponse response = generationRouter.call(
                LlmScene.PROACTIVE_REMINDER,
                prompt,
                null, null, null,
                GenerationCapability.CHAT,
                Duration.ofSeconds(15)
        );

        String content = response.content();
        if (content == null || content.isBlank()) {
            return outcomes.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }

        return parseLlmResponse(content, outcomes, topicIndex);
    }

    private List<ReminderSituation> parseLlmResponse(String content,
                                                      List<ReminderDecisionOutcome> outcomes,
                                                      Map<String, ReminderTopicSnapshot> topicIndex) {
        // 提取 JSON 数组
        String json = extractJsonArray(content);
        if (json == null) {
            return outcomes.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }

        try {
            List<Map<String, Object>> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, ReminderDecisionOutcome> outcomeIndex = outcomes.stream()
                    .collect(Collectors.toMap(
                            o -> o.decision().candidate().topicKey(),
                            o -> o,
                            (a, _) -> a,
                            LinkedHashMap::new
                    ));

            List<ReminderSituation> situations = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                @SuppressWarnings("unchecked")
                List<String> topicKeys = item.get("topicKeys") instanceof List<?> list
                        ? list.stream().map(Object::toString).toList()
                        : List.of();
                String message = item.getOrDefault("message", "").toString();
                String priority = item.getOrDefault("priority", "medium").toString();

                if (topicKeys.isEmpty() || message.isBlank()) {
                    continue;
                }

                List<ReminderDecisionOutcome> relatedDecisions = topicKeys.stream()
                        .map(outcomeIndex::get)
                        .filter(o -> o != null)
                        .toList();

                if (relatedDecisions.isEmpty()) {
                    continue;
                }

                situations.add(new ReminderSituation(topicKeys, message, priority, relatedDecisions));
            }

            // 处理未被 LLM 归组的决策
            var groupedKeys = situations.stream()
                    .flatMap(s -> s.topicKeys().stream())
                    .collect(Collectors.toSet());
            for (ReminderDecisionOutcome outcome : outcomes) {
                if (!groupedKeys.contains(outcome.decision().candidate().topicKey())) {
                    situations.add(wrapAsSingleSituation(outcome, topicIndex));
                }
            }

            return situations;
        } catch (Exception e) {
            log.debug("情境合成 JSON 解析失败: {}", e.getMessage());
            return outcomes.stream()
                    .map(o -> wrapAsSingleSituation(o, topicIndex))
                    .toList();
        }
    }

    @Nullable
    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    private String buildDecisionItemsText(List<ReminderDecisionOutcome> outcomes,
                                          Map<String, ReminderTopicSnapshot> topicIndex) {
        StringBuilder sb = new StringBuilder();
        for (ReminderDecisionOutcome outcome : outcomes) {
            ReminderCandidate candidate = outcome.decision().candidate();
            ReminderTopicSnapshot snapshot = topicIndex.get(candidate.topicKey());
            sb.append("- 【").append(candidate.title()).append("】");
            sb.append(" topicKey=").append(candidate.topicKey());
            sb.append(" 类型=").append(candidate.type().name());
            sb.append(" 紧迫度=").append(String.format("%.2f", candidate.urgencyScore()));
            if (candidate.rationale() != null && !candidate.rationale().isBlank()) {
                sb.append(" 原因=").append(candidate.rationale());
            }
            if (snapshot != null && !snapshot.signals().isEmpty()) {
                ReminderSignal signal = snapshot.signals().getFirst();
                if (signal.relevantAt() != null) {
                    sb.append(" 相关时间=").append(signal.relevantAt().atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMATTER));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildRecentContext(Map<String, ReminderTopicSnapshot> topicIndex) {
        if (topicIndex.isEmpty()) {
            return "无特别关注方向";
        }
        return topicIndex.values().stream()
                .map(ReminderTopicSnapshot::title)
                .distinct()
                .limit(5)
                .collect(Collectors.joining("、"));
    }

    private ReminderSituation wrapAsSingleSituation(ReminderDecisionOutcome outcome,
                                                     Map<String, ReminderTopicSnapshot> topicIndex) {
        String topicKey = outcome.decision().candidate().topicKey();
        return new ReminderSituation(
                List.of(topicKey),
                null,  // message 为 null 时走原有的 MessageGenerator 路径
                outcome.decision().candidate().urgencyScore() >= 0.85f ? "high" : "medium",
                List.of(outcome)
        );
    }
}
