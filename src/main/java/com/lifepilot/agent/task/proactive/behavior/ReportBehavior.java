package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final EpisodicMemory episodicMemory;
    private final AgentConfigProperties config;

    public ReportBehavior(EpisodicMemory episodicMemory,
                          GenerationRouter generationRouter,
                          AgentConfigProperties config,
                          PromptRegistry promptRegistry) {
        super(generationRouter, promptRegistry);
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "情节记忆不能为空");
        this.config = Objects.requireNonNull(config, "Agent 配置不能为空");
    }

    private int dailyReportHour() {
        return config.getTask().getProactiveEngineDailyReportHour();
    }

    @Override
    protected Duration llmTimeout() {
        return Duration.ofSeconds(config.getTask().getProactiveEngineLlmTimeoutSeconds());
    }

    @Override
    public String name() { return "report"; }

    @Override
    public BehaviorLayer layer() { return BehaviorLayer.HABIT_DRIVEN; }

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
     * 覆写内容生成 — 无对话摘要时返回 null（跳过报告），有摘要时走基类 LLM 生成流程。
     */
    @Override
    protected String generateContent(ProactiveCandidate candidate, ContextPacket ctx) {
        String reportType = candidate.detail() instanceof String s ? s : "daily";
        Duration lookback = "weekly".equals(reportType) ? Duration.ofDays(7) : Duration.ofDays(1);
        if (gatherConversationSummaries(lookback).isBlank()) return null;
        return super.generateContent(candidate, ctx);
    }

    @Override
    protected DeliveryLevel suggestLevel(ProactiveCandidate candidate, ContextPacket ctx) {
        return DeliveryLevel.NOTIFY;
    }

    private String gatherConversationSummaries(Duration lookback) {
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
