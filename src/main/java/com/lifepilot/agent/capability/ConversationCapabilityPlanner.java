package com.lifepilot.agent.capability;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对话能力预发现器。
 *
 * <p>在 ReAct 首轮前根据用户目标的强信号词，把少量相关工具预加入
 * {@link ReactAgentState#discoveredToolIds()}，让主对话能自然看到后端能力。
 * Skill 建议只用于前端轻提示，不直接注入工具，也不替代 LLM 的决策。</p>
 *
 * @author zsg
 * @since 2026-07-02
 */
public class ConversationCapabilityPlanner {

    private static final Logger log = LoggerFactory.getLogger(ConversationCapabilityPlanner.class);

    private static final Map<String, CapabilityMeta> CAPABILITY_META = buildCapabilityMeta();
    private static final Map<String, CapabilityMeta> SKILL_META = buildSkillMeta();

    private final DynamicToolRegistry toolRegistry;
    @Nullable
    private final SkillRegistry skillRegistry;
    @Nullable
    private final SkillInstallationRepository skillInstallationRepository;

    public ConversationCapabilityPlanner(DynamicToolRegistry toolRegistry) {
        this(toolRegistry, null, null);
    }

    public ConversationCapabilityPlanner(DynamicToolRegistry toolRegistry,
                                         @Nullable SkillRegistry skillRegistry,
                                         @Nullable SkillInstallationRepository skillInstallationRepository) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.skillInstallationRepository = skillInstallationRepository;
    }

    /**
     * 供前端轻量展示的能力建议。
     *
     * @param id     工具或 Skill ID
     * @param label  用户可读名称
     * @param reason 触发原因
     * @param kind   建议类型：tool / skill
     * @param outputs Skill v3 输出形态；工具建议为空
     */
    public record SuggestedCapability(String id, String label, String reason, String kind, List<String> outputs) {

        public SuggestedCapability(String id, String label, String reason, String kind) {
            this(id, label, reason, kind, List.of());
        }

        public SuggestedCapability {
            kind = (kind == null || kind.isBlank()) ? "tool" : kind;
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        public boolean isTool() {
            return "tool".equals(kind);
        }
    }

    /**
     * SSE payload：本轮预发现的能力。
     *
     * @param traceId Trace ID
     * @param turnId  Turn ID
     * @param tools   建议能力列表
     */
    public record CapabilitySuggestionEvent(String traceId,
                                            @Nullable String turnId,
                                            List<SuggestedCapability> tools) {
        public CapabilitySuggestionEvent {
            tools = tools != null ? List.copyOf(tools) : List.of();
        }
    }

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
                .filter(SuggestedCapability::isTool)
                .map(SuggestedCapability::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (toolIds.isEmpty()) {
            return state;
        }
        log.debug("能力预发现命中: traceId={}, toolIds={}", state.traceId(), toolIds);
        return state.withDiscoveredToolIds(toolIds);
    }

    /**
     * 生成本轮对话的能力建议。只返回当前已注册且尚未暴露的工具。
     *
     * @param state 当前 Agent 状态
     * @return 能力建议列表
     */
    public List<SuggestedCapability> suggest(ReactAgentState state) {
        if (state == null || state.taskMode() == AgentTaskMode.ANSWER) {
            return List.of();
        }
        if (state.allowedToolIds() != null && !state.allowedToolIds().isEmpty()) {
            return List.of();
        }
        Set<String> plannedToolIds = planToolIds(state.goal());
        Set<String> plannedSkillIds = planSkillIds(state.goal());
        if (plannedToolIds.isEmpty()) {
            if (plannedSkillIds.isEmpty()) {
                return List.of();
            }
        }
        Set<String> alreadyVisible = state.discoveredToolIds() != null
                ? state.discoveredToolIds()
                : Set.of();
        var suggestions = new java.util.ArrayList<SuggestedCapability>();
        for (String toolId : plannedToolIds) {
            if (alreadyVisible.contains(toolId) || toolRegistry.resolve(toolId).isEmpty()) {
                continue;
            }
            CapabilityMeta meta = CAPABILITY_META.getOrDefault(toolId,
                    new CapabilityMeta(toolId, "已识别到相关能力"));
            suggestions.add(new SuggestedCapability(toolId, meta.label(), meta.reason(), "tool"));
        }
        for (String skillId : plannedSkillIds) {
            SkillDefinition skill = findAvailableSkill(skillId);
            if (skill == null) {
                continue;
            }
            CapabilityMeta meta = SKILL_META.getOrDefault(skillId,
                    new CapabilityMeta(skillId, "检测到相关任务策略"));
            suggestions.add(new SuggestedCapability(
                    skillId, meta.label(), meta.reason(), "skill", skill.zhiweiMeta().outputs()));
        }
        return List.copyOf(suggestions);
    }

    /**
     * 构建 SSE payload。
     *
     * @param state       当前状态
     * @param suggestions 建议能力
     * @return SSE payload
     */
    public CapabilitySuggestionEvent toEvent(ReactAgentState state, List<SuggestedCapability> suggestions) {
        return new CapabilitySuggestionEvent(state.traceId(), state.turnId(), suggestions);
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

    private static Map<String, CapabilityMeta> buildSkillMeta() {
        var map = new LinkedHashMap<String, CapabilityMeta>();
        map.put("code-assistant", new CapabilityMeta("编码代理", "检测到复杂开发或审查任务"));
        map.put("browser-automation", new CapabilityMeta("浏览器自动化", "检测到网页交互或截图任务"));
        map.put("daily-manager", new CapabilityMeta("日常管理", "检测到规划、回顾或跨任务协调"));
        map.put("research-assistant", new CapabilityMeta("调研策略", "检测到多源调研或事实核查"));
        map.put("data-analyst", new CapabilityMeta("数据分析", "检测到表格、统计或可视化任务"));
        map.put("content-creator", new CapabilityMeta("内容创作", "检测到写作、改写或成稿任务"));
        map.put("summarizer", new CapabilityMeta("摘要整理", "检测到长文、会议或资料压缩任务"));
        map.put("api-debugger", new CapabilityMeta("接口调试", "检测到 API、curl 或响应排查任务"));
        map.put("log-analyzer", new CapabilityMeta("日志分析", "检测到日志、报错或堆栈排查"));
        map.put("healthcheck", new CapabilityMeta("健康诊断", "检测到系统资源或服务检查任务"));
        map.put("file-organizer", new CapabilityMeta("文件整理", "检测到重命名、归档或清理任务"));
        map.put("cron-scheduler", new CapabilityMeta("定时任务", "检测到提醒、周期或定时执行"));
        map.put("a2ui", new CapabilityMeta("交互界面", "检测到需要按钮、表单或可操作结果"));
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
        if (goal == null || goal.isBlank()) {
            return Set.of();
        }
        String text = goal.toLowerCase(Locale.ROOT);
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
        if (containsAny(text, "最新", "今天", "新闻", "联网查", "网上查", "搜索网页",
                "官网", "网页内容", "http://", "https://", "url")) {
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

        return Set.copyOf(result);
    }

    /**
     * 按用户目标规划候选 Skill ID。仅用于轻量提示，真正加载仍由 Agent 决策。
     *
     * @param goal 用户目标
     * @return 候选 Skill ID 集合
     */
    public Set<String> planSkillIds(String goal) {
        if (goal == null || goal.isBlank()) {
            return Set.of();
        }
        String text = goal.toLowerCase(Locale.ROOT);
        var result = new LinkedHashSet<String>();

        if (containsAny(text, "codex", "claude code", "gemini", "多文件", "重构", "修 bug",
                "修复 bug", "代码审查", "pr 审查", "并行任务", "后台 agent")) {
            result.add("code-assistant");
        }
        if (containsAny(text, "打开网页", "浏览器", "点击页面", "网页登录", "验证码",
                "页面操作", "填写网页", "网页截图", "浏览器自动化")) {
            result.add("browser-automation");
        }
        if (containsAny(text, "计划", "优先级", "日报", "周报", "月报", "做到哪",
                "进度回顾", "跨 skill", "跨领域协作", "安排一下")) {
            result.add("daily-manager");
        }
        if (containsAny(text, "调研", "竞品", "事实核查", "真的吗", "趋势", "行业动态",
                "对比分析", "技术选型", "多源", "来源")) {
            result.add("research-assistant");
        }
        if (containsAny(text, "csv", "excel", "xlsx", "数据分析", "pandas", "统计",
                "图表", "可视化")) {
            result.add("data-analyst");
        }
        if (containsAny(text, "写文章", "写报告", "改写", "润色", "文案", "成稿",
                "邮件", "博客")) {
            result.add("content-creator");
        }
        if (containsAny(text, "总结", "摘要", "会议纪要", "长文", "提炼", "压缩")) {
            result.add("summarizer");
        }
        if (containsAny(text, "api", "接口", "curl", "graphql", "http", "响应码",
                "mock")) {
            result.add("api-debugger");
        }
        if (containsAny(text, "日志", "报错", "堆栈", "stack trace", "异常排查")) {
            result.add("log-analyzer");
        }
        if (containsAny(text, "健康检查", "cpu", "内存", "磁盘", "端口", "服务状态")) {
            result.add("healthcheck");
        }
        if (containsAny(text, "整理文件", "重命名", "归档", "重复文件", "清理文件")) {
            result.add("file-organizer");
        }
        if (containsAny(text, "提醒我", "定时", "每天", "每周", "周期性", "计划任务")) {
            result.add("cron-scheduler");
        }
        if (containsAny(text, "可交互", "交互组件", "按钮", "表单", "选择项", "待办列表")) {
            result.add("a2ui");
        }

        return Set.copyOf(result);
    }

    private SkillDefinition findAvailableSkill(String skillId) {
        if (skillRegistry == null) {
            return null;
        }
        var skillOpt = skillRegistry.find(skillId);
        if (skillOpt.isEmpty()) {
            return null;
        }
        if (skillInstallationRepository == null) {
            return skillOpt.get();
        }
        try {
            boolean enabled = skillInstallationRepository.findByName(skillId)
                    .map(com.lifepilot.skill.install.SkillInstallation::enabled)
                    .orElse(true);
            return enabled ? skillOpt.get() : null;
        } catch (Exception e) {
            log.debug("检查 Skill 可用性失败，跳过能力提示: skillId={}, error={}", skillId, e.getMessage());
            return null;
        }
    }

    private boolean containsAny(String text, String... terms) {
        return List.of(terms).stream().anyMatch(text::contains);
    }
}
