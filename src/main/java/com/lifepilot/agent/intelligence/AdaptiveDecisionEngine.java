package com.lifepilot.agent.intelligence;

import jakarta.annotation.PreDestroy;
import com.lifepilot.agent.intelligence.model.DecisionSignal;
import com.lifepilot.agent.intelligence.model.EnvironmentState;
import com.lifepilot.agent.intelligence.model.ToolHealth;
import com.lifepilot.agent.intelligence.config.IntelligenceProperties;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 自适应决策引擎 — 作为"决策顾问"增强 LLM 的决策上下文。
 *
 * <p><b>核心定位</b>：不替代 LLM 决策，而是将经验/能力/环境信号
 * 结构化地注入 ContextAssembler 的上下文中，让 LLM 基于更丰富的
 * 信息做出更好的决策。</p>
 *
 * <p>注入的信号包括：
 * <ul>
 *   <li>历史经验：类似任务的成功/失败模式（来自 IntentMatcher）</li>
 *   <li>能力评估：当前工具的可靠性评分（来自 CapabilityAssessor）</li>
 *   <li>环境状态：时间、用户状态（来自 EnvironmentPerceptor）</li>
 *   <li>风险预警：检测到的潜在风险因素</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class AdaptiveDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveDecisionEngine.class);
    private static final int MAX_EXPERIENCE_MATCH_CACHE_SIZE = 256;
    private static final Duration DEFAULT_RECENT_EXPERIENCE_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_RECENT_EXPERIENCE_MAX = 8;
    private static final int DEFAULT_EXPERIENCE_MATCH_MAX_GOAL_CHARS = 240;
    private static final Duration DEFAULT_EXPERIENCE_MATCH_BACKGROUND_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_TOOL_HEALTH_SIGNAL_MAX_TOOLS = 32;
    private static final int RECOVERY_CONTROL_PREFIX_CHARS = 512;
    private static final int EXPERIENCE_OPT_OUT_CONTROL_PREFIX_CHARS = 96;
    private static final int MIN_EXPERIENCE_MATCH_MAX_GOAL_CHARS = 20;
    private static final List<String> RECOVERY_CONTROL_MARKERS = List.of(
            "<resume_user_input>",
            "<restart_original_user_input>",
            "<restart_instruction>",
            "<task_recovery_checkpoint>"
    );
    private static final List<String> RECOVERY_CONTROL_PHRASES = List.of(
            "从上一轮断点继续。",
            "按上一轮恢复计划继续。",
            "重新开始上一轮任务。",
            "恢复动作：",
            "恢复计划：",
            "续接计划：",
            "请从这个失败点继续",
            "请按恢复计划继续",
            "请重新开始这一轮",
            "保留可复用信息并按恢复计划推进"
    );
    private static final List<String> EXPERIENCE_OPT_OUT_PHRASES = List.of(
            "不要参考历史",
            "别参考历史",
            "不参考历史",
            "不用历史",
            "不要用历史",
            "别用历史",
            "不要参考经验",
            "别参考经验",
            "不参考经验",
            "不用经验",
            "不要用经验",
            "别用经验",
            "不需要经验",
            "不要参考记忆",
            "别参考记忆",
            "不参考记忆",
            "不用记忆",
            "不要用记忆",
            "别用记忆",
            "只基于本轮",
            "只基于这轮",
            "只看本轮",
            "只看这轮",
            "只根据我给的资料",
            "只根据我给的材料",
            "只根据我给的内容",
            "只基于我给的资料",
            "只基于我给的材料",
            "只基于我给的内容",
            "只看我给的资料",
            "只看我给的材料",
            "只看我给的内容",
            "只用我给的资料",
            "只用我给的材料",
            "只用我给的内容",
            "仅根据我给的资料",
            "仅根据我给的材料",
            "仅根据我给的内容",
            "仅使用我给的资料",
            "仅使用我给的材料",
            "仅使用我给的内容",
            "只根据上传资料",
            "只根据上传附件",
            "只根据附件",
            "只看上传资料",
            "只看上传附件",
            "只看附件",
            "仅根据上传资料",
            "仅根据上传附件",
            "仅根据附件",
            "只根据原文",
            "只基于原文",
            "只看原文",
            "仅根据原文",
            "只根据当前材料",
            "只基于当前材料",
            "只看当前材料",
            "不用工具",
            "不要用工具",
            "不要调用工具",
            "别调用工具",
            "不调用工具",
            "不要使用工具",
            "别使用工具",
            "跳过经验匹配",
            "跳过意图识别",
            "不要做意图识别",
            "不用意图识别",
            "别做意图识别",
            "不做意图识别",
            "别分析意图",
            "不要分析意图",
            "不分析意图",
            "无需分析意图",
            "跳过能力预发现",
            "跳过能力核查",
            "不要预判能力",
            "别预判能力",
            "不用预判能力",
            "不做能力预判",
            "别做能力预判",
            "无需能力核查",
            "直接回答",
            "直接处理",
            "skip intent",
            "skip experience",
            "skip memory",
            "skip tools",
            "without history",
            "do not use history",
            "don't use history",
            "no history",
            "without memory",
            "no memory",
            "without tools",
            "no tools",
            "only use provided material",
            "only use provided materials",
            "only use provided context",
            "only use the provided material",
            "only use the provided materials",
            "only use the provided context",
            "use only provided material",
            "use only provided materials",
            "use only provided context",
            "direct answer",
            "answer directly"
    );
    private static final Pattern TASK_LIKE_ENGLISH_PATTERN = Pattern.compile(
            "\\b(help|please|find|search|research|analy[sz]e|summari[sz]e|compare|write|create|generate|fix|implement|optimi[sz]e|run|execute|test|commit|deploy|backup|import|export|remember|remind|schedule|plan|translate|explain|install|configure|check|debug|review)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> TASK_LIKE_CHINESE_MARKERS = List.of(
            "帮我", "请帮", "请你", "请先", "请把", "请将", "请基于", "请给", "请为",
            "需要", "我要", "我想", "能不能", "可以帮",
            "整理", "总结", "查找", "查询", "搜索", "调研", "分析", "对比", "解释",
            "写", "生成", "创建", "修复", "实现", "优化", "修改", "改成", "运行",
            "执行", "测试", "提交", "部署", "备份", "导入", "导出", "记住", "记下",
            "提醒", "安排", "计划", "翻译", "检查", "复盘", "读取", "打开", "安装",
            "配置", "同步");
    private static final List<String> TASK_LIKE_TECH_MARKERS = List.of(
            "```", "`", ".java", ".ts", ".tsx", ".vue", ".py", ".md", ".json",
            "src/", "src\\", "npm ", "mvn ", "git ", "http://", "https://");

    private final CapabilityAssessor capabilityAssessor;
    private final EnvironmentPerceptor environmentPerceptor;
    private final IntentMatcher intentMatcher;
    private final float minExperienceConfidence;
    private final Duration experienceMatchTimeout;
    private final IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger;
    private final int experienceMatchMinGoalChars;
    private final int experienceMatchMaxGoalChars;
    private final int experienceMatchMaxPending;
    private final Duration experienceMatchRecentTtl;
    private final int experienceMatchRecentMax;
    private final Duration experienceMatchBackgroundTimeout;
    private final int toolHealthSignalMaxTools;
    private final ExecutorService experienceMatchExecutor;
    private final Object experienceMatchLock = new Object();
    private final Map<String, CompletableFuture<DecisionSignal.ExperienceHint>> experienceMatchCache =
            new ConcurrentHashMap<>();
    private final Set<String> runningExperienceMatchGoals = ConcurrentHashMap.newKeySet();
    private final Map<String, RecentExperienceHint> recentExperienceHints = new ConcurrentHashMap<>();

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  IntentMatcher intentMatcher,
                                  float minExperienceConfidence,
                                  Duration experienceMatchTimeout,
                                  IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
                                  int experienceMatchMinGoalChars,
                                  int experienceMatchMaxPending) {
        this(capabilityAssessor, environmentPerceptor, intentMatcher, minExperienceConfidence,
                experienceMatchTimeout, experienceMatchTrigger, experienceMatchMinGoalChars,
                DEFAULT_EXPERIENCE_MATCH_MAX_GOAL_CHARS, experienceMatchMaxPending,
                DEFAULT_RECENT_EXPERIENCE_TTL, DEFAULT_RECENT_EXPERIENCE_MAX,
                DEFAULT_EXPERIENCE_MATCH_BACKGROUND_TIMEOUT);
    }

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  IntentMatcher intentMatcher,
                                  float minExperienceConfidence,
                                  Duration experienceMatchTimeout,
                                  IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
                                  int experienceMatchMinGoalChars,
                                  int experienceMatchMaxPending,
                                  Duration experienceMatchRecentTtl,
                                  int experienceMatchRecentMax) {
        this(capabilityAssessor, environmentPerceptor, intentMatcher, minExperienceConfidence,
                experienceMatchTimeout, experienceMatchTrigger, experienceMatchMinGoalChars,
                DEFAULT_EXPERIENCE_MATCH_MAX_GOAL_CHARS, experienceMatchMaxPending,
                experienceMatchRecentTtl, experienceMatchRecentMax,
                DEFAULT_EXPERIENCE_MATCH_BACKGROUND_TIMEOUT);
    }

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  IntentMatcher intentMatcher,
                                  float minExperienceConfidence,
                                  Duration experienceMatchTimeout,
                                  IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
                                  int experienceMatchMinGoalChars,
                                  int experienceMatchMaxGoalChars,
                                  int experienceMatchMaxPending,
                                  Duration experienceMatchRecentTtl,
                                  int experienceMatchRecentMax) {
        this(capabilityAssessor, environmentPerceptor, intentMatcher, minExperienceConfidence,
                experienceMatchTimeout, experienceMatchTrigger, experienceMatchMinGoalChars,
                experienceMatchMaxGoalChars, experienceMatchMaxPending,
                experienceMatchRecentTtl, experienceMatchRecentMax,
                DEFAULT_EXPERIENCE_MATCH_BACKGROUND_TIMEOUT);
    }

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  IntentMatcher intentMatcher,
                                  float minExperienceConfidence,
                                  Duration experienceMatchTimeout,
                                  IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
                                  int experienceMatchMinGoalChars,
                                  int experienceMatchMaxGoalChars,
                                  int experienceMatchMaxPending,
                                  Duration experienceMatchRecentTtl,
                                  int experienceMatchRecentMax,
                                  Duration experienceMatchBackgroundTimeout) {
        this(capabilityAssessor, environmentPerceptor, intentMatcher, minExperienceConfidence,
                experienceMatchTimeout, experienceMatchTrigger, experienceMatchMinGoalChars,
                experienceMatchMaxGoalChars, experienceMatchMaxPending,
                experienceMatchRecentTtl, experienceMatchRecentMax,
                experienceMatchBackgroundTimeout, DEFAULT_TOOL_HEALTH_SIGNAL_MAX_TOOLS);
    }

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  IntentMatcher intentMatcher,
                                  float minExperienceConfidence,
                                  Duration experienceMatchTimeout,
                                  IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
                                  int experienceMatchMinGoalChars,
                                  int experienceMatchMaxGoalChars,
                                  int experienceMatchMaxPending,
                                  Duration experienceMatchRecentTtl,
                                  int experienceMatchRecentMax,
                                  Duration experienceMatchBackgroundTimeout,
                                  int toolHealthSignalMaxTools) {
        if (minExperienceConfidence < 0.0f || minExperienceConfidence > 1.0f) {
            throw new IllegalArgumentException("经验匹配最低置信度必须在 0 到 1 之间");
        }
        if (experienceMatchTimeout == null || experienceMatchTimeout.isNegative()) {
            throw new IllegalArgumentException("经验匹配超时时间不能为负");
        }
        if (experienceMatchMinGoalChars < 0) {
            throw new IllegalArgumentException("经验匹配最少有效字符数不能为负");
        }
        if (experienceMatchMaxGoalChars < MIN_EXPERIENCE_MATCH_MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("经验匹配最大输入字符数不能小于 "
                    + MIN_EXPERIENCE_MATCH_MAX_GOAL_CHARS);
        }
        if (experienceMatchMaxPending < 0) {
            throw new IllegalArgumentException("经验匹配最大后台任务数不能为负");
        }
        if (experienceMatchRecentTtl == null || experienceMatchRecentTtl.isNegative()) {
            throw new IllegalArgumentException("经验匹配 recent 缓存时间不能为负");
        }
        if (experienceMatchRecentMax < 0) {
            throw new IllegalArgumentException("经验匹配 recent 缓存数量不能为负");
        }
        if (experienceMatchBackgroundTimeout == null || experienceMatchBackgroundTimeout.isNegative()) {
            throw new IllegalArgumentException("经验匹配后台任务超时时间不能为负");
        }
        if (toolHealthSignalMaxTools < 0) {
            throw new IllegalArgumentException("决策信号工具健康扫描上限不能为负");
        }
        this.capabilityAssessor = Objects.requireNonNull(capabilityAssessor, "能力评估器不能为空");
        this.environmentPerceptor = Objects.requireNonNull(environmentPerceptor, "环境感知器不能为空");
        this.intentMatcher = Objects.requireNonNull(intentMatcher, "意图匹配器不能为空");
        this.minExperienceConfidence = minExperienceConfidence;
        this.experienceMatchTimeout = experienceMatchTimeout;
        this.experienceMatchTrigger = Objects.requireNonNull(experienceMatchTrigger, "经验匹配触发策略不能为空");
        this.experienceMatchMinGoalChars = experienceMatchMinGoalChars;
        this.experienceMatchMaxGoalChars = experienceMatchMaxGoalChars;
        this.experienceMatchMaxPending = experienceMatchMaxPending;
        this.experienceMatchRecentTtl = experienceMatchRecentTtl;
        this.experienceMatchRecentMax = experienceMatchRecentMax;
        this.experienceMatchBackgroundTimeout = experienceMatchBackgroundTimeout;
        this.toolHealthSignalMaxTools = toolHealthSignalMaxTools;
        this.experienceMatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 构建决策增强信号 — 在 ContextAssembler.assemble() 中调用。
     *
     * <p>返回结构化的决策信号，由 ContextAssembler 格式化后注入上下文消息。</p>
     *
     * @param goal             用户目标/意图文本
     * @param availableToolIds 当前可用工具集
     * @return 决策信号（可能为空信号，表示无额外建议）
     */
    public DecisionSignal buildDecisionSignal(String goal, Set<String> availableToolIds) {
        return buildDecisionSignal(goal, availableToolIds, true);
    }

    /**
     * 构建决策增强信号。
     *
     * @param goal              用户目标/意图文本
     * @param availableToolIds  当前可用工具集
     * @param includeExperience 是否允许读取历史经验；本轮关闭记忆上下文时应为 false
     * @return 决策信号（可能为空信号，表示无额外建议）
     */
    public DecisionSignal buildDecisionSignal(String goal, Set<String> availableToolIds, boolean includeExperience) {
        // 1. 匹配历史经验
        var experienceHint = includeExperience ? matchExperience(goal) : null;

        Set<String> signalToolIds = decisionSignalToolIds(availableToolIds);

        // 2. 评估工具能力
        var toolHints = assessTools(signalToolIds);

        // 3. 感知环境
        var environment = environmentPerceptor.perceive(signalToolIds);
        var environmentHint = formatEnvironmentHint(environment);

        // 4. 检测风险
        var risks = detectRisks(environment);

        var signal = new DecisionSignal(experienceHint, toolHints, risks, environmentHint);

        if (!signal.isEmpty()) {
            log.debug("决策引擎: 生成信号, experience={}, tools={}, risks={}",
                    experienceHint != null ? experienceHint.templateName() : "none",
                    toolHints.size(), risks.size());
        }

        return signal;
    }

    /**
     * 格式化决策信号为可注入 Prompt 的文本。
     *
     * @param signal 决策信号
     * @return 格式化文本（为空时返回 null）
     */
    @Nullable
    public String formatForPrompt(DecisionSignal signal) {
        if (signal.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("- 以下是内部执行策略提示，只用于选择行动；最终回答不要提及本段、标签或来源。\n");

        if (signal.experienceHint() != null) {
            var hint = signal.experienceHint();
            sb.append("- 可沿用的做法: ").append(hint.pattern());
            if (hint.caveat() != null) {
                sb.append("；注意: ").append(hint.caveat());
            }
            sb.append("\n");
        }

        if (!signal.toolHints().isEmpty()) {
            for (var tool : signal.toolHints()) {
                if (tool.issue() != null) {
                    sb.append("- 使用 ").append(tool.toolId())
                            .append(" 前先评估替代路径: ").append(tool.issue()).append("\n");
                }
            }
        }

        if (!signal.risks().isEmpty()) {
            for (var risk : signal.risks()) {
                sb.append("- 风险提醒: ").append(risk.description()).append("\n");
            }
        }

        if (signal.environmentHint() != null) {
            sb.append("- 环境提醒: ").append(signal.environmentHint()).append("\n");
        }

        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }

    @Nullable
    private DecisionSignal.ExperienceHint matchExperience(String goal) {
        if (goal == null || goal.isBlank()) {
            return null;
        }
        String normalizedGoal = goal.trim();
        if (isRecoveryControlGoal(normalizedGoal)) {
            log.debug("决策引擎: 恢复控制文本跳过经验匹配");
            return null;
        }
        if (isExperienceOptOutGoal(normalizedGoal)) {
            log.debug("决策引擎: 用户要求跳过历史经验/意图增强");
            return null;
        }
        if (experienceMatchTrigger == IntelligenceProperties.ExperienceMatchTrigger.DISABLED) {
            log.debug("决策引擎: 经验匹配策略已关闭，跳过后台增强");
            return null;
        }
        String matchGoal = buildExperienceMatchGoal(normalizedGoal);
        if (shouldSkipExperienceMatch(matchGoal)) {
            return null;
        }
        var recentHint = findRecentExperienceHint(matchGoal);
        if (recentHint != null) {
            return recentHint;
        }
        pruneExperienceMatchCacheIfNeeded();
        var future = findOrStartExperienceMatch(matchGoal);
        if (future == null) {
            return null;
        }
        if (future.isDone()) {
            return readCompletedExperienceHint(matchGoal, future);
        }
        if (experienceMatchTimeout.isZero()) {
            log.debug("决策引擎: 经验匹配后台执行，主链路不等待: goal={}", matchGoal);
            return null;
        }
        try {
            return future.get(experienceMatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("决策引擎: 经验匹配超过 {}ms，跳过本轮注入: goal={}",
                    experienceMatchTimeout.toMillis(), matchGoal);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("决策引擎: 经验匹配等待被中断，跳过本轮注入: goal={}", matchGoal);
            return null;
        } catch (Exception e) {
            experienceMatchCache.remove(matchGoal, future);
            log.debug("决策引擎: 经验匹配失败，跳过本轮注入: goal={}, error={}",
                    matchGoal, e.getMessage());
            return null;
        }
    }

    private String buildExperienceMatchGoal(String normalizedGoal) {
        if (normalizedGoal.length() <= experienceMatchMaxGoalChars) {
            return normalizedGoal;
        }
        String marker = "\n...\n";
        int available = Math.max(2, experienceMatchMaxGoalChars - marker.length());
        int headChars = Math.max(1, available / 2);
        int tailChars = Math.max(1, available - headChars);
        String head = firstCodePoints(normalizedGoal, headChars).stripTrailing();
        String tail = lastCodePoints(normalizedGoal, tailChars).stripLeading();
        String compacted = (head + marker + tail).strip();
        log.debug("决策引擎: 经验匹配输入过长，裁剪为意图探针: originalLength={}, probeLength={}",
                normalizedGoal.length(), compacted.length());
        return compacted;
    }

    private String firstCodePoints(String value, int count) {
        if (count <= 0 || value.isEmpty()) {
            return "";
        }
        int end = 0;
        int remaining = count;
        while (end < value.length() && remaining > 0) {
            end += Character.charCount(value.codePointAt(end));
            remaining--;
        }
        return value.substring(0, end);
    }

    private String lastCodePoints(String value, int count) {
        if (count <= 0 || value.isEmpty()) {
            return "";
        }
        int start = value.length();
        int remaining = count;
        while (start > 0 && remaining > 0) {
            int codePoint = value.codePointBefore(start);
            start -= Character.charCount(codePoint);
            remaining--;
        }
        return value.substring(start);
    }

    private CompletableFuture<DecisionSignal.ExperienceHint> startExperienceMatch(String normalizedGoal) {
        runningExperienceMatchGoals.add(normalizedGoal);
        CompletableFuture<DecisionSignal.ExperienceHint> rawFuture = new CompletableFuture<>();
        Future<?> runningTask = experienceMatchExecutor.submit(() -> {
            try {
                rawFuture.complete(matchExperienceBlocking(normalizedGoal));
            } catch (Throwable error) {
                rawFuture.completeExceptionally(error);
            } finally {
                runningExperienceMatchGoals.remove(normalizedGoal);
            }
        });
        CompletableFuture<DecisionSignal.ExperienceHint> future =
                withBackgroundTimeout(normalizedGoal, rawFuture, runningTask);
        future.whenComplete((hint, error) -> {
            if (error != null) {
                experienceMatchCache.remove(normalizedGoal, future);
                log.debug("决策引擎: 后台经验匹配失败: goal={}, error={}",
                        normalizedGoal, error.getMessage());
                return;
            }
            if (hint != null) {
                rememberExperienceHint(normalizedGoal, hint);
            }
        });
        return future;
    }

    private CompletableFuture<DecisionSignal.ExperienceHint> withBackgroundTimeout(
            String normalizedGoal,
            CompletableFuture<DecisionSignal.ExperienceHint> rawFuture,
            Future<?> runningTask) {
        if (experienceMatchBackgroundTimeout.isZero()) {
            return rawFuture;
        }
        CompletableFuture<DecisionSignal.ExperienceHint> timedFuture = new CompletableFuture<>();
        rawFuture.whenComplete((hint, error) -> {
            if (error != null) {
                timedFuture.completeExceptionally(error);
            } else {
                timedFuture.complete(hint);
            }
        });
        CompletableFuture.delayedExecutor(
                        experienceMatchBackgroundTimeout.toMillis(),
                        TimeUnit.MILLISECONDS)
                .execute(() -> {
                    TimeoutException timeout = new TimeoutException("后台经验匹配超过 "
                            + experienceMatchBackgroundTimeout.toMillis() + "ms");
                    if (timedFuture.completeExceptionally(timeout)) {
                        runningTask.cancel(true);
                        log.debug("决策引擎: 后台经验匹配超过 {}ms，放弃本轮结果并尝试中断: goal={}",
                                experienceMatchBackgroundTimeout.toMillis(), normalizedGoal);
                    }
                });
        return timedFuture;
    }

    private long pendingExperienceMatchCount() {
        return runningExperienceMatchGoals.size();
    }

    @Nullable
    private CompletableFuture<DecisionSignal.ExperienceHint> findOrStartExperienceMatch(String matchGoal) {
        synchronized (experienceMatchLock) {
            var future = experienceMatchCache.get(matchGoal);
            if (future != null) {
                return future;
            }
            if (runningExperienceMatchGoals.contains(matchGoal)) {
                log.debug("决策引擎: 同一经验匹配仍在后台执行，跳过重复启动: goal={}", matchGoal);
                return null;
            }
            if (pendingExperienceMatchCount() >= experienceMatchMaxPending) {
                log.debug("决策引擎: 后台经验匹配任务已达上限 {}，跳过本轮增强: goal={}",
                        experienceMatchMaxPending, matchGoal);
                return null;
            }
            future = startExperienceMatch(matchGoal);
            experienceMatchCache.put(matchGoal, future);
            return future;
        }
    }

    private boolean shouldSkipExperienceMatch(String normalizedGoal) {
        int informativeChars = informativeCharacterCount(normalizedGoal);
        if (informativeChars < experienceMatchMinGoalChars) {
            log.debug("决策引擎: 输入有效字符数 {} 低于阈值 {}，跳过经验匹配",
                    informativeChars, experienceMatchMinGoalChars);
            return true;
        }
        if (experienceMatchTrigger == IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE
                && !looksTaskLike(normalizedGoal)) {
            log.debug("决策引擎: 输入未命中任务型 fast-path，跳过经验匹配: goal={}", normalizedGoal);
            return true;
        }
        return false;
    }

    private boolean looksTaskLike(String normalizedGoal) {
        String lower = normalizedGoal.toLowerCase();
        if (TASK_LIKE_ENGLISH_PATTERN.matcher(lower).find()) {
            return true;
        }
        for (String marker : TASK_LIKE_CHINESE_MARKERS) {
            if (normalizedGoal.contains(marker)) {
                return true;
            }
        }
        for (String marker : TASK_LIKE_TECH_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return normalizedGoal.contains("\n- ")
                || normalizedGoal.contains("\n1.")
                || normalizedGoal.contains("\n#");
    }

    private boolean isRecoveryControlGoal(String normalizedGoal) {
        String controlPrefix = firstCodePoints(normalizedGoal, RECOVERY_CONTROL_PREFIX_CHARS);
        for (String marker : RECOVERY_CONTROL_MARKERS) {
            if (controlPrefix.contains(marker)) {
                return true;
            }
        }
        for (String phrase : RECOVERY_CONTROL_PHRASES) {
            if (controlPrefix.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExperienceOptOutGoal(String normalizedGoal) {
        String controlSegment = leadingControlSegment(normalizedGoal).toLowerCase(Locale.ROOT);
        if (controlSegment.isBlank()) {
            return false;
        }
        for (String phrase : EXPERIENCE_OPT_OUT_PHRASES) {
            if (isDirectAnswerPhrase(phrase)) {
                if (isDirectAnswerCommand(controlSegment, phrase)) {
                    return true;
                }
                continue;
            }
            if (controlSegment.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectAnswerPhrase(String phrase) {
        return phrase.equals("直接回答")
                || phrase.equals("直接处理")
                || phrase.equals("direct answer")
                || phrase.equals("answer directly");
    }

    private boolean isDirectAnswerCommand(String controlSegment, String phrase) {
        String segment = controlSegment.strip();
        return segment.startsWith(phrase)
                || segment.startsWith("请" + phrase)
                || segment.startsWith("请你" + phrase)
                || segment.startsWith("你" + phrase)
                || segment.startsWith("这次" + phrase)
                || segment.startsWith("本次" + phrase)
                || segment.startsWith("please " + phrase);
    }

    private String leadingControlSegment(String normalizedGoal) {
        String prefix = firstCodePoints(normalizedGoal, EXPERIENCE_OPT_OUT_CONTROL_PREFIX_CHARS);
        int firstLineEnd = prefix.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? prefix.substring(0, firstLineEnd) : prefix;
        int contentDelimiter = firstContentDelimiter(firstLine);
        String segment = contentDelimiter >= 0 ? firstLine.substring(0, contentDelimiter) : firstLine;
        return segment.strip();
    }

    private int firstContentDelimiter(String value) {
        int delimiter = -1;
        for (String candidate : List.of("：", ":", "\n\n")) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (delimiter < 0 || index < delimiter)) {
                delimiter = index;
            }
        }
        return delimiter;
    }

    private int informativeCharacterCount(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                count += 1;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    @Nullable
    private DecisionSignal.ExperienceHint readCompletedExperienceHint(
            String normalizedGoal,
            CompletableFuture<DecisionSignal.ExperienceHint> future) {
        try {
            var hint = future.join();
            if (hint != null) {
                rememberExperienceHint(normalizedGoal, hint);
            }
            return hint;
        } catch (Exception e) {
            experienceMatchCache.remove(normalizedGoal, future);
            log.debug("决策引擎: 读取后台经验匹配结果失败，跳过本轮注入: goal={}, error={}",
                    normalizedGoal, e.getMessage());
            return null;
        }
    }

    @Nullable
    private DecisionSignal.ExperienceHint matchExperienceBlocking(String normalizedGoal) {
        var match = intentMatcher.match(normalizedGoal);
        if (match.isEmpty() || match.get().score() < minExperienceConfidence) {
            return null;
        }
        var template = match.get().template();
        return new DecisionSignal.ExperienceHint(
                template.name(),
                match.get().score(),
                template.description() != null ? template.description() : template.name(),
                template.successRate() < 0.7f ? "历史成功率偏低(" + template.successRate() + ")" : null
        );
    }

    private void pruneExperienceMatchCacheIfNeeded() {
        if (experienceMatchCache.size() < MAX_EXPERIENCE_MATCH_CACHE_SIZE) {
            return;
        }
        experienceMatchCache.entrySet().removeIf(entry -> entry.getValue().isDone());
        if (experienceMatchCache.size() >= MAX_EXPERIENCE_MATCH_CACHE_SIZE) {
            experienceMatchCache.clear();
        }
    }

    private void rememberExperienceHint(String normalizedGoal, DecisionSignal.ExperienceHint hint) {
        if (experienceMatchRecentTtl.isZero() || experienceMatchRecentMax == 0) {
            return;
        }
        recentExperienceHints.put(normalizedGoal, new RecentExperienceHint(hint, Instant.now()));
        pruneRecentExperienceHints();
    }

    @Nullable
    private DecisionSignal.ExperienceHint findRecentExperienceHint(String normalizedGoal) {
        if (experienceMatchRecentTtl.isZero() || experienceMatchRecentMax == 0) {
            return null;
        }
        pruneRecentExperienceHints();
        return recentExperienceHints.entrySet().stream()
                .filter(entry -> isRelatedToRecentGoal(normalizedGoal, entry.getKey()))
                .max(Comparator.comparing(entry -> entry.getValue().matchedAt()))
                .map(entry -> entry.getValue().hint())
                .orElse(null);
    }

    private void pruneRecentExperienceHints() {
        if (recentExperienceHints.isEmpty()) {
            return;
        }
        if (experienceMatchRecentTtl.isZero() || experienceMatchRecentMax == 0) {
            recentExperienceHints.clear();
            return;
        }
        Instant cutoff = Instant.now().minus(experienceMatchRecentTtl);
        recentExperienceHints.entrySet().removeIf(entry -> entry.getValue().matchedAt().isBefore(cutoff));
        if (recentExperienceHints.size() <= experienceMatchRecentMax) {
            return;
        }
        recentExperienceHints.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().matchedAt()))
                .limit(recentExperienceHints.size() - experienceMatchRecentMax)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(recentExperienceHints::remove);
    }

    private boolean isRelatedToRecentGoal(String current, String previous) {
        String compactCurrent = compactRelatedText(current);
        String compactPrevious = compactRelatedText(previous);
        if (compactCurrent.equals(compactPrevious)) {
            return true;
        }
        int minLength = Math.min(compactCurrent.length(), compactPrevious.length());
        if (minLength >= Math.max(4, experienceMatchMinGoalChars)
                && (compactCurrent.contains(compactPrevious) || compactPrevious.contains(compactCurrent))) {
            return true;
        }

        Set<String> currentTokens = relatedTokens(current);
        Set<String> previousTokens = relatedTokens(previous);
        if (currentTokens.isEmpty() || previousTokens.isEmpty()) {
            return false;
        }
        long shared = currentTokens.stream().filter(previousTokens::contains).count();
        int smaller = Math.min(currentTokens.size(), previousTokens.size());
        return shared >= 2 && ((float) shared / smaller) >= 0.5f;
    }

    private String compactRelatedText(String value) {
        StringBuilder builder = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toLowerCase(codePoint));
            }
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private Set<String> relatedTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < lower.length(); ) {
            int codePoint = lower.codePointAt(offset);
            if (isHan(codePoint)) {
                flushAsciiToken(tokens, ascii);
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHanTokens(tokens, han);
                ascii.appendCodePoint(codePoint);
            } else {
                flushAsciiToken(tokens, ascii);
                flushHanTokens(tokens, han);
            }
            offset += Character.charCount(codePoint);
        }
        flushAsciiToken(tokens, ascii);
        flushHanTokens(tokens, han);
        return tokens;
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private void flushAsciiToken(Set<String> tokens, StringBuilder token) {
        if (token.length() >= 3) {
            tokens.add(token.toString());
        }
        token.setLength(0);
    }

    private void flushHanTokens(Set<String> tokens, StringBuilder token) {
        if (token.length() >= 2) {
            for (int index = 0; index < token.length() - 1; index += 1) {
                tokens.add(token.substring(index, index + 2));
            }
        }
        token.setLength(0);
    }

    private Set<String> decisionSignalToolIds(Set<String> availableToolIds) {
        if (availableToolIds == null || availableToolIds.isEmpty() || toolHealthSignalMaxTools == 0) {
            return Set.of();
        }
        if (availableToolIds.size() <= toolHealthSignalMaxTools) {
            return Set.copyOf(availableToolIds);
        }
        log.debug("决策引擎: 可用工具数量 {} 超过健康信号扫描上限 {}，跳过工具健康提示",
                availableToolIds.size(), toolHealthSignalMaxTools);
        return Set.of();
    }

    private List<DecisionSignal.ToolCapabilityHint> assessTools(Set<String> toolIds) {
        var hints = new ArrayList<DecisionSignal.ToolCapabilityHint>();
        for (String toolId : toolIds) {
            var health = capabilityAssessor.getToolHealth(toolId);
            // 只对有数据且不健康的工具生成提示
            if (health.recentSuccesses() + health.recentFailures() > 0 && !health.isHealthy()) {
                hints.add(new DecisionSignal.ToolCapabilityHint(
                        toolId,
                        health.successRate(),
                        "最近成功率 " + String.format("%.0f%%", health.successRate() * 100)
                                + (health.lastError() != null ? ", 最近错误: " + truncate(health.lastError(), 50) : "")
                ));
            }
        }
        return hints;
    }

    private List<DecisionSignal.RiskWarning> detectRisks(EnvironmentState env) {
        var risks = new ArrayList<DecisionSignal.RiskWarning>();

        // 检测不健康工具
        for (var entry : env.toolHealth().entrySet()) {
            if (!entry.getValue().isHealthy() && entry.getValue().recentFailures() >= 3) {
                risks.add(new DecisionSignal.RiskWarning(
                        "TOOL_DEGRADED",
                        entry.getKey() + " 最近频繁失败，建议使用替代方案",
                        0.7f
                ));
            }
        }

        return risks;
    }

    @Nullable
    private String formatEnvironmentHint(EnvironmentState env) {
        if (!env.timeContext().isWorkingHours()) {
            return "非工作时间";
        }
        return null;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    @PreDestroy
    public void shutdown() {
        experienceMatchExecutor.shutdownNow();
    }

    private record RecentExperienceHint(
            DecisionSignal.ExperienceHint hint,
            Instant matchedAt
    ) {}
}
