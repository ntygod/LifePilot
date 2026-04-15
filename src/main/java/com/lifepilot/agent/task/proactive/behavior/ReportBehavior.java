package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.*;
import java.util.*;

/**
 * 日报/周报行为插件 — 在固定时段汇总活动、进展和待办。
 *
 * <p>detect: 检查当前是否在日报/周报时段（每天 20:00-21:00 / 每周五）。
 * reason: 汇总近期对话，生成报告摘要。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ReportBehavior extends AbstractLlmBehavior {

    private static final Logger log = LoggerFactory.getLogger(ReportBehavior.class);
    private static final DayOfWeek WEEKLY_REPORT_DAY = DayOfWeek.FRIDAY;
    private static final String PROMPT_KEY = "generation/proactive-report";

    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final AgentConfigProperties config;

    public ReportBehavior(@Nullable EpisodicMemory episodicMemory,
                          @Nullable GenerationRouter generationRouter,
                          @Nullable AgentConfigProperties config,
                          @Nullable PromptRegistry promptRegistry) {
        super(generationRouter, promptRegistry);
        this.episodicMemory = episodicMemory;
        this.config = config;
    }

    private int dailyReportHour() {
        return config != null ? config.getTask().getProactiveEngineDailyReportHour() : 20;
    }

    @Override
    protected Duration llmTimeout() {
        return Duration.ofSeconds(config != null ? config.getTask().getProactiveEngineLlmTimeoutSeconds() : 15);
    }

    @Override
    public String name() { return "report"; }

    @Override
    protected String promptKey() { return PROMPT_KEY; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        LocalDateTime localNow = LocalDateTime.ofInstant(ctx.now(), ctx.zoneId());
        int hour = localNow.getHour();
        DayOfWeek dayOfWeek = localNow.getDayOfWeek();

        var candidates = new ArrayList<ProactiveCandidate>();

        // 日报：每天 20:00-21:00 时段
        if (hour == dailyReportHour()) {
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "daily-report-" + localNow.toLocalDate(),
                    "今日小结",
                    0.5f, "每日报告时段", "daily"));
        }

        // 周报：每周五 20:00-21:00
        if (hour == dailyReportHour() && dayOfWeek == WEEKLY_REPORT_DAY) {
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "weekly-report-" + localNow.toLocalDate(),
                    "本周总结",
                    0.55f, "每周报告时段", "weekly"));
        }
        return candidates;
    }

    @Override
    protected Map<String, Object> buildPromptVariables(ProactiveCandidate candidate, ContextPacket ctx) {
        String reportType = candidate.detail() instanceof String s ? s : "daily";
        Duration lookback = "weekly".equals(reportType) ? Duration.ofDays(7) : Duration.ofDays(1);
        String summaryInput = gatherConversationSummaries(lookback);
        String period = "weekly".equals(reportType) ? "本周" : "今天";

        var vars = new HashMap<String, Object>();
        vars.put("reportType", period);
        vars.put("currentTime", formatTime(ctx));
        vars.put("conversationSummaries", summaryInput);
        return vars;
    }

    /**
     * 覆写内容生成 — 无对话摘要时返回 null（跳过报告），有摘要时走基类 LLM+回退流程。
     */
    @Override
    protected String generateContent(ProactiveCandidate candidate, ContextPacket ctx) {
        String reportType = candidate.detail() instanceof String s ? s : "daily";
        Duration lookback = "weekly".equals(reportType) ? Duration.ofDays(7) : Duration.ofDays(1);
        if (gatherConversationSummaries(lookback).isBlank()) return null;
        return super.generateContent(candidate, ctx);
    }

    @Override
    protected String fallbackContent(ProactiveCandidate candidate) {
        String reportType = candidate.detail() instanceof String s ? s : "daily";
        return "weekly".equals(reportType)
                ? "本周你和我聊了不少话题，要看看本周总结吗？"
                : "今天的对话有一些值得回顾的内容，要看看今日小结吗？";
    }

    @Override
    protected DeliveryLevel suggestLevel(ProactiveCandidate candidate) {
        return DeliveryLevel.NOTIFY;
    }

    private String gatherConversationSummaries(Duration lookback) {
        if (episodicMemory == null) return "";
        try {
            var recent = episodicMemory.getRecent(lookback);
            if (recent.isEmpty()) return "";
            var sb = new StringBuilder();
            for (var conv : recent) {
                if (conv.summary() != null && !conv.summary().isBlank()) {
                    sb.append("- ").append(conv.summary()).append("\n");
                } else if (conv.goal() != null && !conv.goal().isBlank()) {
                    sb.append("- ").append(conv.goal()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("ReportBehavior: 对话检索失败: {}", e.getMessage());
            return "";
        }
    }
}
