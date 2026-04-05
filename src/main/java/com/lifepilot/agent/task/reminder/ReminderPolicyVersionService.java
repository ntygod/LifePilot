package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 主动提醒策略版本服务。
 *
 * <p>把当前调优结果映射为稳定版本，
 * 并在同一用户下复用相同配置签名对应的历史版本。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderPolicyVersionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReminderPolicyVersionRepository repository;

    public ReminderPolicyVersionService(ReminderPolicyVersionRepository repository) {
        this.repository = repository;
    }

    public ReminderPolicyVersionRecord resolve(String userId,
                                               ReminderPolicyConfig config,
                                               ReminderUserFeedbackSummary feedbackSummary,
                                               @Nullable ReminderReplayReport replayReport,
                                               Instant now) {
        return resolve(userId, config, feedbackSummary, replayReport, null, now);
    }

    public ReminderPolicyVersionRecord resolve(String userId,
                                               ReminderPolicyConfig config,
                                               ReminderUserFeedbackSummary feedbackSummary,
                                               @Nullable ReminderReplayReport replayReport,
                                               @Nullable ReminderPolicyGuardrailResult guardrailResult,
                                               Instant now) {
        String configJson = serializeConfig(config);
        String signature = sha256Hex(configJson);
        return repository.findByUserIdAndConfigSignature(userId, signature)
                .orElseGet(() -> createVersion(userId, configJson, signature, feedbackSummary, replayReport,
                        guardrailResult, now));
    }

    public Optional<ReminderPolicyVersionRecord> findLatestByUserId(String userId) {
        return repository.findLatestByUserId(userId);
    }

    public ReminderPolicyConfig parseConfig(ReminderPolicyVersionRecord record) {
        if (record == null || record.configJson() == null || record.configJson().isBlank()) {
            return new ReminderPolicyConfig();
        }
        try {
            Map<String, Object> config = MAPPER.readValue(record.configJson(), Map.class);
            return new ReminderPolicyConfig(
                    asInt(config.get("dueSoonThresholdHours"), 24),
                    asInt(config.get("commitmentGapThresholdHours"), 18),
                    asInt(config.get("dailyMaxReminders"), 3),
                    asInt(config.get("defaultCooldownHours"), 24),
                    asInt(config.get("preferredWindowLookaheadMinutes"), 60),
                    asFloat(config.get("minFinalScore"), 0.55f),
                    asFloat(config.get("softPushThreshold"), 0.63f),
                    asFloat(config.get("strongPushThreshold"), 0.78f),
                    asFloat(config.get("anomalyThreshold"), 0.65f)
            );
        } catch (Exception e) {
            throw new IllegalStateException("主动提醒策略版本化失败: 历史配置解析异常", e);
        }
    }

    public ReminderPolicyVersionInsight parseInsight(ReminderPolicyVersionRecord record) {
        if (record == null || record.summaryJson() == null || record.summaryJson().isBlank()) {
            return ReminderPolicyVersionInsight.empty();
        }
        try {
            Map<String, Object> summary = MAPPER.readValue(record.summaryJson(), Map.class);
            Object replayObject = summary.get("replay");
            if (!(replayObject instanceof Map<?, ?> replay)) {
                return ReminderPolicyVersionInsight.empty();
            }
            int sampleCount = asInt(replay.get("sampleCount"), 0);
            int promotedCount = asInt(replay.get("promotedCount"), 0);
            int suppressedCount = asInt(replay.get("suppressedCount"), 0);
            float expectedDelta = asFloat(replay.get("expectedDelta"),
                    asFloat(replay.get("replayedEstimatedPushRewardMean"), 0.0f)
                            - asFloat(replay.get("historicalEstimatedPushRewardMean"), 0.0f));
            ReminderPolicyAdjustmentDirection direction = ReminderPolicyAdjustmentDirection.NEUTRAL;
            if (promotedCount > suppressedCount) {
                direction = ReminderPolicyAdjustmentDirection.LOOSER;
            } else if (suppressedCount > promotedCount) {
                direction = ReminderPolicyAdjustmentDirection.TIGHTER;
            }
            return new ReminderPolicyVersionInsight(direction, sampleCount, expectedDelta, sampleCount > 0);
        } catch (Exception e) {
            throw new IllegalStateException("主动提醒策略版本化失败: 历史摘要解析异常", e);
        }
    }

    private ReminderPolicyVersionRecord createVersion(String userId,
                                                      String configJson,
                                                      String signature,
                                                      ReminderUserFeedbackSummary feedbackSummary,
                                                      @Nullable ReminderReplayReport replayReport,
                                                      @Nullable ReminderPolicyGuardrailResult guardrailResult,
                                                      Instant now) {
        ReminderPolicyVersionRecord record = new ReminderPolicyVersionRecord(
                UUID.randomUUID().toString(),
                userId,
                repository.nextVersion(userId),
                signature,
                configJson,
                resolveSource(feedbackSummary, replayReport),
                serializeSummary(feedbackSummary, replayReport, guardrailResult),
                now,
                now
        );
        repository.save(record);
        return record;
    }

    private String serializeConfig(ReminderPolicyConfig config) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "dueSoonThresholdHours", config.dueSoonThresholdHours(),
                    "commitmentGapThresholdHours", config.commitmentGapThresholdHours(),
                    "dailyMaxReminders", config.dailyMaxReminders(),
                    "defaultCooldownHours", config.defaultCooldownHours(),
                    "preferredWindowLookaheadMinutes", config.preferredWindowLookaheadMinutes(),
                    "minFinalScore", config.minFinalScore(),
                    "softPushThreshold", config.softPushThreshold(),
                    "strongPushThreshold", config.strongPushThreshold(),
                    "anomalyThreshold", config.anomalyThreshold()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("主动提醒策略版本化失败: 配置序列化异常", e);
        }
    }

    private String serializeSummary(ReminderUserFeedbackSummary feedbackSummary,
                                    @Nullable ReminderReplayReport replayReport,
                                    @Nullable ReminderPolicyGuardrailResult guardrailResult) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("feedback", Map.of(
                    "actedCount", feedbackSummary.actedCount(),
                    "snoozedCount", feedbackSummary.snoozedCount(),
                    "dismissedCount", feedbackSummary.dismissedCount(),
                    "notRelevantCount", feedbackSummary.notRelevantCount(),
                    "mutedTopicCount", feedbackSummary.mutedTopicCount(),
                    "totalFeedbackCount", feedbackSummary.totalFeedbackCount()
            ));
            if (replayReport != null) {
                Map<String, Object> replay = new LinkedHashMap<>();
                replay.put("sampleCount", replayReport.sampleCount());
                replay.put("actionShiftCount", replayReport.actionShiftCount());
                replay.put("promotedCount", replayReport.promotedCount());
                replay.put("suppressedCount", replayReport.suppressedCount());
                replay.put("historicalPushCount", replayReport.historicalPushCount());
                replay.put("replayedPushCount", replayReport.replayedPushCount());
                replay.put("historicalEstimatedPushRewardMean", replayReport.historicalEstimatedPushRewardMean());
                replay.put("replayedEstimatedPushRewardMean", replayReport.replayedEstimatedPushRewardMean());
                replay.put("expectedDelta", replayReport.replayedEstimatedPushRewardMean()
                        - replayReport.historicalEstimatedPushRewardMean());
                summary.put("replay", replay);
            }
            if (guardrailResult != null) {
                Map<String, Object> guardrail = new LinkedHashMap<>();
                guardrail.put("adjusted", guardrailResult.adjusted());
                guardrail.put("rolledBack", guardrailResult.rolledBack());
                guardrail.put("summary", guardrailResult.summary());
                summary.put("guardrail", guardrail);
            }
            return MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("主动提醒策略版本化失败: 摘要序列化异常", e);
        }
    }

    private String resolveSource(ReminderUserFeedbackSummary feedbackSummary,
                                 @Nullable ReminderReplayReport replayReport) {
        boolean hasFeedback = feedbackSummary.totalFeedbackCount() > 0 || feedbackSummary.mutedTopicCount() > 0;
        boolean hasReplay = replayReport != null && replayReport.sampleCount() > 0;
        if (hasFeedback && hasReplay) {
            return "feedback+replay";
        }
        if (hasFeedback) {
            return "feedback";
        }
        if (hasReplay) {
            return "replay";
        }
        return "base";
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("主动提醒策略版本化失败: 签名生成异常", e);
        }
    }

    private int asInt(@Nullable Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private float asFloat(@Nullable Object value, float defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return Float.parseFloat(value.toString());
    }
}
