package com.lifepilot.agent.capability;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对话工具预发现器。
 *
 * <p>在 ReAct 首轮前根据用户目标的强信号词，把少量相关工具预加入
 * {@link ReactAgentState#discoveredToolIds()}，帮助 Agent 更快获得可执行工具。
 * 预发现只服务内部执行，不向主对话推送预测性能力提示。</p>
 *
 * @author zsg
 * @since 2026-07-02
 */
public class ConversationCapabilityPlanner {

    private static final Logger log = LoggerFactory.getLogger(ConversationCapabilityPlanner.class);
    public static final int DEFAULT_CONTROL_PREFIX_CHARS = 96;
    public static final int DEFAULT_PLANNING_PROBE_MAX_CHARS = 320;

    private static final Map<String, CapabilityMeta> CAPABILITY_META = buildCapabilityMeta();
    private static final List<String> PRE_DISCOVERY_OPT_OUT_PHRASES = List.of(
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
            "不要预发现工具",
            "不要预判能力",
            "别预判能力",
            "不用预判能力",
            "不做能力预判",
            "别做能力预判",
            "无需能力核查",
            "不用判断",
            "不要判断",
            "别判断",
            "无需判断",
            "不用工具",
            "不要用工具",
            "不要调用工具",
            "别调用工具",
            "不调用工具",
            "不要使用工具",
            "别使用工具",
            "直接回答",
            "直接处理",
            "直接做",
            "直接开始",
            "直接执行",
            "直接帮我",
            "skip intent",
            "skip tools",
            "no tools",
            "without tools",
            "direct answer",
            "answer directly",
            "just do it",
            "do it directly"
    );
    private static final List<String> WEB_ACCESS_OPT_OUT_PHRASES = List.of(
            "不要联网",
            "不联网",
            "不用联网",
            "别联网",
            "不要上网",
            "不用上网",
            "不要搜索网页",
            "不用搜索网页",
            "不要搜索",
            "不用搜索",
            "不要查网上",
            "不要联网检索",
            "不用联网检索",
            "别联网检索",
            "不要查资料",
            "不用查资料",
            "别查资料",
            "只用本地",
            "只用已有资料",
            "只根据我给的资料",
            "只根据我给的材料",
            "只根据我给的内容",
            "只根据上传资料",
            "只根据上传附件",
            "只根据附件",
            "只根据原文",
            "仅根据我给的资料",
            "仅根据我给的材料",
            "仅根据我给的内容",
            "仅根据上传资料",
            "仅根据上传附件",
            "仅根据附件",
            "仅根据原文",
            "只看我给的资料",
            "只看我给的材料",
            "只看附件",
            "仅使用本地资料",
            "offline",
            "no web",
            "without web",
            "no internet",
            "without internet",
            "do not search"
    );
    private static final List<String> SOURCE_MATERIAL_MARKERS = List.of(
            "下面资料",
            "以下资料",
            "下面材料",
            "以下材料",
            "下面内容",
            "以下内容",
            "这段资料",
            "这段材料",
            "这段内容",
            "这段文本",
            "下面文本",
            "以下文本",
            "我贴的资料",
            "我贴的内容",
            "我给的资料",
            "我给的材料",
            "我给的内容",
            "我发的资料",
            "我发的材料",
            "我发的内容",
            "上传的资料",
            "上传资料",
            "上传的附件",
            "上传附件",
            "附件内容",
            "附件里",
            "原文"
    );
    private static final List<String> EXTERNAL_LOOKUP_MARKERS = List.of(
            "联网",
            "上网",
            "搜索",
            "查网上",
            "网页",
            "官网",
            "链接",
            "url",
            "http://",
            "https://",
            "核对来源",
            "核实来源",
            "核验来源",
            "核对出处",
            "核实出处",
            "查证"
    );

    private final DynamicToolRegistry toolRegistry;
    private final boolean enabled;
    private final int controlPrefixChars;
    private final int planningProbeMaxChars;

    public ConversationCapabilityPlanner(DynamicToolRegistry toolRegistry) {
        this(toolRegistry, true, DEFAULT_CONTROL_PREFIX_CHARS, DEFAULT_PLANNING_PROBE_MAX_CHARS);
    }

    public ConversationCapabilityPlanner(DynamicToolRegistry toolRegistry,
                                         boolean enabled,
                                         int controlPrefixChars,
                                         int planningProbeMaxChars) {
        if (controlPrefixChars <= 0) {
            throw new IllegalArgumentException("能力预发现控制前缀字符数必须大于 0");
        }
        if (planningProbeMaxChars < 20) {
            throw new IllegalArgumentException("能力预发现探针最大字符数不能小于 20");
        }
        this.toolRegistry = toolRegistry;
        this.enabled = enabled;
        this.controlPrefixChars = controlPrefixChars;
        this.planningProbeMaxChars = planningProbeMaxChars;
    }

    /**
     * 内部工具预发现建议。
     *
     * @param id     工具 ID
     * @param label  可读名称
     * @param reason 触发原因
     */
    public record SuggestedCapability(String id, String label, String reason) {}

    /**
     * 将保守匹配到的能力工具合并进当前状态。
     *
     * @param state 当前 Agent 状态
     * @return 合并预发现工具后的状态
     */
    public ReactAgentState enrich(ReactAgentState state) {
        List<SuggestedCapability> suggestions = suggest(state);
        if (suggestions.isEmpty()) {
            return state;
        }
        Set<String> toolIds = suggestions.stream()
                .map(SuggestedCapability::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (toolIds.isEmpty()) {
            return state;
        }
        log.debug("能力预发现命中: traceId={}, toolIds={}", state.traceId(), toolIds);
        return state.withDiscoveredToolIds(toolIds);
    }

    /**
     * 生成本轮对话的内部工具建议。只返回当前已注册且尚未暴露的工具。
     *
     * @param state 当前 Agent 状态
     * @return 能力建议列表
     */
    public List<SuggestedCapability> suggest(ReactAgentState state) {
        if (!enabled || state == null || state.taskMode() == AgentTaskMode.ANSWER) {
            return List.of();
        }
        if (state.allowedToolIds() != null && !state.allowedToolIds().isEmpty()) {
            return List.of();
        }
        Set<String> plannedToolIds = planToolIds(state.goal());
        if (plannedToolIds.isEmpty()) {
            return List.of();
        }
        Set<String> alreadyVisible = state.discoveredToolIds() != null
                ? state.discoveredToolIds()
                : Set.of();
        Set<String> disabledToolIds = state.disabledToolIds() != null
                ? Set.copyOf(state.disabledToolIds())
                : Set.of();
        var suggestions = new java.util.ArrayList<SuggestedCapability>();
        for (String toolId : plannedToolIds) {
            if (alreadyVisible.contains(toolId)
                    || isToolDisabled(toolId, disabledToolIds)
                    || toolRegistry.resolve(toolId).isEmpty()) {
                continue;
            }
            CapabilityMeta meta = CAPABILITY_META.getOrDefault(toolId,
                    new CapabilityMeta(toolId, "已识别到相关能力"));
            suggestions.add(new SuggestedCapability(toolId, meta.label(), meta.reason()));
        }
        return List.copyOf(suggestions);
    }

    private boolean isToolDisabled(String toolId, Set<String> disabledToolIds) {
        if (disabledToolIds.isEmpty()) {
            return false;
        }
        if (disabledToolIds.contains(toolId)) {
            return true;
        }
        return disabledToolIds.stream()
                .anyMatch(disabledId -> toolId.startsWith(disabledId + "."));
    }

    private static Map<String, CapabilityMeta> buildCapabilityMeta() {
        var map = new LinkedHashMap<String, CapabilityMeta>();
        map.put("git.query", new CapabilityMeta("查看仓库", "检测到 Git 查询或差异意图"));
        map.put("git.mutate", new CapabilityMeta("提交分支", "检测到提交、分支或暂存意图"));
        map.put("transcript.search", new CapabilityMeta("回看工具结果", "检测到历史工具原文需求"));
        map.put("transcript.get", new CapabilityMeta("取完整原文", "检测到 callId 或 entryId 回看需求"));
        map.put("ui.render", new CapabilityMeta("交互组件", "检测到按钮、表单或可点击选项需求"));
        map.put("file.attach", new CapabilityMeta("挂载附件", "检测到展示生成文件或图片需求"));
        map.put("file.write", new CapabilityMeta("编辑文件", "检测到写入或修改文件需求"));
        map.put("file.manage", new CapabilityMeta("管理文件", "检测到移动、复制或整理文件需求"));
        map.put("browser", new CapabilityMeta("操作浏览器", "检测到网页交互需求"));
        map.put("web.search", new CapabilityMeta("联网搜索", "检测到最新信息或网页查询需求"));
        map.put("web.fetch", new CapabilityMeta("读取网页", "检测到网页内容读取需求"));
        map.put("code", new CapabilityMeta("运行代码", "检测到计算、脚本或数据分析需求"));
        map.put("shell.exec", new CapabilityMeta("执行命令", "检测到终端或命令行需求"));
        map.put("shell.process", new CapabilityMeta("跟踪进程", "检测到长任务或服务进程需求"));
        map.put("cron", new CapabilityMeta("安排提醒", "检测到定时或周期跟进需求"));
        map.put("notify", new CapabilityMeta("发送通知", "检测到通知或推送需求"));
        return Map.copyOf(map);
    }

    private record CapabilityMeta(String label, String reason) {}

    /**
     * 按用户目标规划候选工具 ID。仅用于测试和轻量预发现，最终会再经过注册表过滤。
     *
     * @param goal 用户目标
     * @return 候选工具 ID 集合
     */
    public Set<String> planToolIds(String goal) {
        if (!enabled) {
            return Set.of();
        }
        if (goal == null || goal.isBlank()) {
            return Set.of();
        }
        String normalizedGoal = goal.strip();
        String controlSegment = leadingControlSegment(normalizedGoal);
        if (shouldSkipPreDiscovery(controlSegment)) {
            return Set.of();
        }
        String probe = buildPlanningProbe(normalizedGoal, controlSegment);
        String text = probe.toLowerCase(Locale.ROOT);
        var result = new LinkedHashSet<String>();

        if (containsAny(text, "git", "仓库", "工作区状态", "diff", "log", "blame", "提交历史", "差异")) {
            result.add("git.query");
        }
        if (containsAny(text, "git commit", "提交代码", "提交改动", "创建分支", "切换分支",
                "删除分支", "stash", "暂存改动", "分支")) {
            result.add("git.mutate");
        }
        if (containsAny(text, "callid", "entryid", "transcript", "工具调用原文", "工具结果原文",
                "完整原文", "上次工具", "之前工具", "刚才搜索结果")) {
            result.add("transcript.search");
            result.add("transcript.get");
        }
        if (containsAny(text, "可交互", "交互组件", "按钮", "表单", "选择项", "点击后",
                "勾选", "待办列表", "筛选 chip", "ui 组件")) {
            result.add("ui.render");
        }
        if (containsAny(text, "生成的文件", "下载入口", "挂载附件", "发给我文件", "展示图片",
                "渲染图片", "attachmentid", "附件下载")) {
            result.add("file.attach");
        }
        if (containsAny(text, "写文件", "创建文件", "修改文件", "编辑文件", "替换内容",
                "插入内容", "删除行")) {
            result.add("file.write");
        }
        if (containsAny(text, "移动文件", "复制文件", "重命名文件", "删除文件", "整理文件",
                "创建目录", "删除目录")) {
            result.add("file.manage");
        }
        if (containsAny(text, "打开网页", "浏览器", "点击页面", "网页登录", "验证码",
                "页面操作", "填写网页", "浏览器自动化")) {
            result.add("browser");
        }
        if (!shouldAvoidWebAccess(controlSegment) && shouldPlanWebAccess(text)) {
            result.add("web.search");
            result.add("web.fetch");
        }
        if (containsAny(text, "运行代码", "执行代码", "python", "脚本", "pandas", "数据分析",
                "画图", "图表", "计算一下", "验证代码")) {
            result.add("code");
        }
        if (containsAny(text, "命令行", "终端", "shell", "powershell", "cmd", "启动服务",
                "安装依赖", "npm ", "mvn ", "查看日志")) {
            result.add("shell.exec");
            result.add("shell.process");
        }
        if (containsAny(text, "提醒我", "定时", "每天", "每周", "明天提醒", "稍后提醒",
                "周期性", "计划任务", "定期检查")) {
            result.add("cron");
        }
        if (containsAny(text, "通知我", "推送", "发通知", "桌面通知")) {
            result.add("notify");
        }

        return Collections.unmodifiableSet(result);
    }

    private String buildPlanningProbe(String normalizedGoal, String controlSegment) {
        if (shouldUseControlSegmentOnly(normalizedGoal, controlSegment)) {
            return controlSegment;
        }
        if (normalizedGoal.length() <= planningProbeMaxChars) {
            return normalizedGoal;
        }
        String marker = "\n...\n";
        int available = Math.max(2, planningProbeMaxChars - marker.length());
        int headChars = Math.max(1, available * 2 / 3);
        int tailChars = Math.max(1, available - headChars);
        String head = firstCodePoints(normalizedGoal, headChars).stripTrailing();
        String tail = lastCodePoints(normalizedGoal, tailChars).stripLeading();
        String compacted = (head + marker + tail).strip();
        log.debug("能力预发现: 用户输入过长，仅使用头尾意图探针: originalLength={}, probeLength={}",
                normalizedGoal.length(), compacted.length());
        return compacted;
    }

    private boolean shouldUseControlSegmentOnly(String normalizedGoal, String controlSegment) {
        if (controlSegment.isBlank() || controlSegment.equals(normalizedGoal)) {
            return false;
        }
        String lowerControl = controlSegment.toLowerCase(Locale.ROOT);
        boolean sourceMaterialTask = containsAny(lowerControl, SOURCE_MATERIAL_MARKERS);
        if (!sourceMaterialTask) {
            return false;
        }
        return !containsAny(lowerControl, EXTERNAL_LOOKUP_MARKERS);
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

    private boolean containsAny(String text, String... terms) {
        return List.of(terms).stream().anyMatch(text::contains);
    }

    private boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private boolean shouldPlanWebAccess(String text) {
        if (containsAny(text, "联网查", "网上查", "搜索网页", "官网", "网页内容",
                "http://", "https://", "url")) {
            return true;
        }
        if (containsAny(text, "最新", "新闻", "资讯", "实时", "现在的", "当前的")) {
            return true;
        }
        if (!containsAny(text, "今天", "今日", "昨天", "明天", "本周", "这个月")) {
            return false;
        }
        return containsAny(text, "查", "搜", "调研", "新闻", "资讯", "动态", "行情", "官网", "网页");
    }

    private boolean shouldSkipPreDiscovery(String controlSegment) {
        controlSegment = controlSegment.toLowerCase(Locale.ROOT);
        if (controlSegment.isBlank()) {
            return false;
        }
        for (String phrase : PRE_DISCOVERY_OPT_OUT_PHRASES) {
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

    private boolean shouldAvoidWebAccess(String controlSegment) {
        controlSegment = controlSegment.toLowerCase(Locale.ROOT);
        if (controlSegment.isBlank()) {
            return false;
        }
        for (String phrase : WEB_ACCESS_OPT_OUT_PHRASES) {
            if (controlSegment.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectAnswerPhrase(String phrase) {
        return phrase.equals("直接回答")
                || phrase.equals("直接处理")
                || phrase.equals("直接做")
                || phrase.equals("直接开始")
                || phrase.equals("直接执行")
                || phrase.equals("直接帮我")
                || phrase.equals("direct answer")
                || phrase.equals("answer directly")
                || phrase.equals("just do it")
                || phrase.equals("do it directly");
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

    private String leadingControlSegment(String goal) {
        String normalized = goal.strip();
        String prefix = firstCodePoints(normalized, controlPrefixChars);
        int firstLineEnd = prefix.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? prefix.substring(0, firstLineEnd) : prefix;
        int delimiter = firstContentDelimiter(firstLine);
        String segment = delimiter >= 0 ? firstLine.substring(0, delimiter) : firstLine;
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
}
