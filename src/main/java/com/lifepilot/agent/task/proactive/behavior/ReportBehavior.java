package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
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
public class ReportBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ReportBehavior.class);
    private static final DayOfWeek WEEKLY_REPORT_DAY = DayOfWeek.FRIDAY;

    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final AgentConfigProperties config;

    public ReportBehavior(@Nullable EpisodicMemory episodicMemory,
                          @Nullable GenerationRouter generationRouter) {
        this(episodicMemory, generationRouter, null);
    }

    public ReportBehavior(@Nullable EpisodicMemory episodicMemory,
                          @Nullable GenerationRouter generationRouter,
                          @Nullable AgentConfigProperties config) {
        this.episodicMemory = episodicMemory;
        this.generationRouter = generationRouter;
        this.config = config;
    }

    private int dailyReportHour() {
        return config != null ? config.getTask().getProactiveEngineDailyReportHour() : 20;
    }

    private Duration llmTimeout() {
        return Duration.ofSeconds(config != null ? config.getTask().getProactiveEngineLlmTimeoutSeconds() : 15);
    }

    @Override
    public String name() { return "report"; }

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
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String reportType = candidate.detail() instanceof String s ? s : "daily";
            String content = generateReport(reportType, ctx);
            if (content == null || content.isBlank()) continue;
            actions.add(new ProactiveAction(candidate, content, DeliveryLevel.NOTIFY, reportType));
        }
        return actions;
    }

    private String generateReport(String type, ContextPacket ctx) {
        Duration lookback = "weekly".equals(type) ? Duration.ofDays(7) : Duration.ofDays(1);
        String summaryInput = gatherConversationSummaries(lookback);

        if (generationRouter != null && !summaryInput.isBlank()) {
            try {
                String period = "weekly".equals(type) ? "本周" : "今天";
                String prompt = "你是个人助手。请基于以下对话摘要生成" + period + "的简报。\n"
                        + "要求：3-5 个要点，每点一句话，不超过 150 字。语气简洁自然，不要用 Markdown。\n\n"
                        + "对话摘要：\n" + summaryInput;
                LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                        GenerationCapability.CHAT, llmTimeout());
                if (response != null && !response.content().isBlank()) return response.content().strip();
            } catch (Exception e) {
                log.debug("ReportBehavior: LLM 失败: {}", e.getMessage());
            }
        }

        if (summaryInput.isBlank()) return null;
        return "weekly".equals(type)
                ? "本周你和我聊了不少话题，要看看本周总结吗？"
                : "今天的对话有一些值得回顾的内容，要看看今日小结吗？";
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
