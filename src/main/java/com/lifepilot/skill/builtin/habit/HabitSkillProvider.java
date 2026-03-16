package com.lifepilot.skill.builtin.habit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.Signal;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.adapter.CrudAdapterConfig;
import com.lifepilot.datastore.adapter.DataStoreCrudAdapter;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;
import com.lifepilot.notification.Urgency;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.ProactiveSkillProvider;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 习惯养成内置 Skill 提供者。
 *
 * <p>注册 7 个习惯管理工具到 DynamicToolRegistry，
 * 提供习惯养成 Skill 定义蓝图。实现 {@link ProactiveSkillProvider}，
 * 提供习惯打卡提醒和连续打卡风险信号源及候选提供者。</p>
 *
 * <p>存储层通过 {@link DataStoreCrudAdapter} 委托给 DataStore，
 * 数据以 JSON 格式存储在"习惯"Collection 中。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "habit", order = 30)
public class HabitSkillProvider implements ProactiveSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(HabitSkillProvider.class);

    private final DataStoreCrudAdapter<HabitEntity> habitAdapter;
    private final PromptRegistry promptRegistry;

    public HabitSkillProvider(DataStoreManager dataStoreManager,
                              ObjectMapper objectMapper,
                              PromptRegistry promptRegistry) {
        this.habitAdapter = new DataStoreCrudAdapter<>(dataStoreManager, objectMapper,
                new CrudAdapterConfig<>(
                        "habit",
                        "习惯",
                        CollectionType.DOCUMENT,
                        HabitEntity.class,
                        List.of(
                                new PropertyDefinition("name", PropertyType.TEXT, true, "习惯名称"),
                                new PropertyDefinition("frequency", PropertyType.SELECT, true, "频率: DAILY/WEEKLY"),
                                new PropertyDefinition("targetCount", PropertyType.NUMBER, true, "目标打卡次数"),
                                new PropertyDefinition("currentStreak", PropertyType.NUMBER, false, "当前连续打卡天数")
                        ),
                        "习惯养成管理"
                ));
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("habit")
                .name("习惯养成")
                .description("管理习惯养成，支持创建、查询、打卡、连续天数统计和完成率计算")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/habit"))
                .suggestedTools(List.of(
                        "builtin.habit.create",
                        "builtin.habit.list",
                        "builtin.habit.get",
                        "builtin.habit.update",
                        "builtin.habit.checkin",
                        "builtin.habit.streak",
                        "builtin.habit.completion-rate"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildCreateTool());
        toolRegistry.registerBuiltinTool(buildListTool());
        toolRegistry.registerBuiltinTool(buildGetTool());
        toolRegistry.registerBuiltinTool(buildUpdateTool());
        toolRegistry.registerBuiltinTool(buildCheckinTool());
        toolRegistry.registerBuiltinTool(buildStreakTool());
        toolRegistry.registerBuiltinTool(buildCompletionRateTool());
        log.info("习惯 Skill 工具注册完成: count=7");
    }

    // ---- ProactiveSkillProvider 实现 ----

    @Override
    public List<SignalSource> signalSources() {
        return List.of(new HabitSignalSource());
    }

    @Override
    public List<CandidateProvider> candidateProviders() {
        return List.of(new HabitCandidateProvider());
    }

    // ---- 工具构建方法 ----

    /** 构建创建习惯工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.create")
                .name("创建习惯")
                .description("创建新的习惯，支持设置名称、频率和目标打卡次数")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "frequency"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "习惯名称"),
                                "frequency", Map.of("type", "string", "description", "频率: DAILY/WEEKLY"),
                                "targetCount", Map.of("type", "integer", "description", "目标打卡次数，默认 0")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String frequency = input.getParam("frequency", String.class).toUpperCase();
                        int targetCount = input.getOptionalParam("targetCount", Integer.class).orElse(0);
                        var entity = new HabitEntity(name, frequency, targetCount, 0, null);
                        return habitAdapter.create(entity);
                    } catch (Exception e) {
                        log.error("创建习惯失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建习惯失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询习惯列表工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.list")
                .name("查询习惯列表")
                .description("查询所有习惯列表")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        List<HabitEntity> entities = habitAdapter.list(
                                null, "name", null, 0, 100);
                        List<Map<String, Object>> itemMaps = entities.stream()
                                .map(this::habitEntityToMap)
                                .toList();
                        return ToolResult.success(Map.of("items", itemMaps));
                    } catch (Exception e) {
                        log.error("查询习惯列表失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询习惯列表失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询单个习惯工具。 */
    private BuiltinTool buildGetTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.get")
                .name("查询习惯详情")
                .description("根据 ID 查询单个习惯的详细信息")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        return habitAdapter.findById(id)
                                .map(entity -> ToolResult.success(habitEntityToMap(entity)))
                                .orElse(ToolResult.error("习惯不存在: id=" + id));
                    } catch (Exception e) {
                        log.error("查询习惯详情失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询习惯详情失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建更新习惯工具。 */
    private BuiltinTool buildUpdateTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.update")
                .name("更新习惯")
                .description("更新习惯的名称、频率或目标打卡次数")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID"),
                                "name", Map.of("type", "string", "description", "新名称"),
                                "frequency", Map.of("type", "string", "description", "新频率: DAILY/WEEKLY"),
                                "targetCount", Map.of("type", "integer", "description", "新目标打卡次数")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = habitAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("习惯不存在: id=" + id);
                        }
                        HabitEntity current = existing.get();
                        String name = input.getOptionalParam("name", String.class).orElse(current.name());
                        String frequency = input.getOptionalParam("frequency", String.class)
                                .map(String::toUpperCase).orElse(current.frequency());
                        int targetCount = input.getOptionalParam("targetCount", Integer.class)
                                .orElse(current.targetCount());
                        var updated = new HabitEntity(name, frequency, targetCount,
                                current.currentStreak(), current.lastCompletedAt());
                        return habitAdapter.update(id, updated);
                    } catch (Exception e) {
                        log.error("更新习惯失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新习惯失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建习惯打卡工具 — findById → 更新 currentStreak+1 和 lastCompletedAt。 */
    private BuiltinTool buildCheckinTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.checkin")
                .name("习惯打卡")
                .description("为指定习惯打卡，自动更新连续打卡天数和最后完成时间")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = habitAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("习惯不存在: id=" + id);
                        }
                        HabitEntity current = existing.get();
                        var updated = new HabitEntity(
                                current.name(),
                                current.frequency(),
                                current.targetCount(),
                                current.currentStreak() + 1,
                                Instant.now().toString());
                        return habitAdapter.update(id, updated);
                    } catch (Exception e) {
                        log.error("习惯打卡失败: {}", e.getMessage(), e);
                        return ToolResult.error("习惯打卡失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建连续打卡天数查询工具 — findById → 返回 currentStreak。 */
    private BuiltinTool buildStreakTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.streak")
                .name("查询连续打卡天数")
                .description("查询指定习惯的当前连续打卡天数")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = habitAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("习惯不存在: id=" + id);
                        }
                        HabitEntity entity = existing.get();
                        return ToolResult.success(Map.of(
                                "name", entity.name(),
                                "currentStreak", entity.currentStreak()));
                    } catch (Exception e) {
                        log.error("查询连续打卡天数失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询连续打卡天数失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建完成率查询工具 — 简化实现：返回 currentStreak 作为代理指标。 */
    private BuiltinTool buildCompletionRateTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.completion-rate")
                .name("查询习惯完成率")
                .description("查询指定习惯的完成率（基于当前连续打卡天数和目标打卡次数）")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = habitAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("习惯不存在: id=" + id);
                        }
                        HabitEntity entity = existing.get();
                        // 简化实现：用 currentStreak / targetCount 作为完成率代理指标
                        double rate = entity.targetCount() > 0
                                ? Math.min(1.0, (double) entity.currentStreak() / entity.targetCount())
                                : 0.0;
                        return ToolResult.success(Map.of(
                                "name", entity.name(),
                                "currentStreak", entity.currentStreak(),
                                "targetCount", entity.targetCount(),
                                "completionRate", rate));
                    } catch (Exception e) {
                        log.error("查询习惯完成率失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询习惯完成率失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 主动推理内部类 ----

    /**
     * 习惯打卡提醒信号源 — 收集当日尚未打卡的 DAILY 习惯信号。
     *
     * <p>对于 DAILY 习惯，如果 lastCompletedAt 不是今天，则生成提醒信号。
     * 根据当前时间判断紧急度：晚上 → HIGH，下午 → MEDIUM，其余 → LOW。</p>
     */
    private class HabitSignalSource implements SignalSource {

        @Override
        public String id() {
            return "habit-signal";
        }

        @Override
        public List<Signal> collect() {
            List<HabitEntity> habits = habitAdapter.list(null, null, null, 0, 1000);
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();
            List<Signal> signals = new ArrayList<>();

            for (HabitEntity habit : habits) {
                if (!"DAILY".equalsIgnoreCase(habit.frequency())) {
                    continue;
                }
                // 检查今天是否已打卡
                boolean checkedInToday = false;
                if (habit.lastCompletedAt() != null) {
                    try {
                        LocalDate lastDate = Instant.parse(habit.lastCompletedAt())
                                .atZone(ZoneId.systemDefault()).toLocalDate();
                        checkedInToday = lastDate.equals(today);
                    } catch (Exception e) {
                        log.warn("解析习惯最后完成时间失败: name={}, lastCompletedAt={}",
                                habit.name(), habit.lastCompletedAt(), e);
                    }
                }
                if (!checkedInToday) {
                    Urgency urgency = now.isAfter(LocalTime.of(20, 0)) ? Urgency.HIGH
                            : now.isAfter(LocalTime.of(14, 0)) ? Urgency.MEDIUM
                            : Urgency.LOW;
                    signals.add(Signal.builder()
                            .typeId("habit_checkin_reminder")
                            .urgency(urgency)
                            .summary("习惯「%s」今日尚未打卡".formatted(habit.name()))
                            .sourceId("habit-signal")
                            .subjectId(habit.name())
                            .metadata(Map.of("name", habit.name(),
                                    "currentStreak", String.valueOf(habit.currentStreak())))
                            .build());
                }
            }
            log.debug("习惯信号收集完成: count={}", signals.size());
            return List.copyOf(signals);
        }
    }

    /**
     * 习惯候选提供者 — 将 habit_checkin_reminder 信号映射为 NOTIFICATION 候选。
     */
    private class HabitCandidateProvider implements CandidateProvider {

        @Override
        public String id() {
            return "habit-candidate";
        }

        @Override
        public List<ProactiveCandidate> evaluate(SignalBundle signals) {
            return signals.signals().stream()
                    .filter(s -> "habit_checkin_reminder".equals(s.typeId()))
                    .filter(s -> "habit-signal".equals(s.sourceId()))
                    .map(s -> new ProactiveCandidate(
                            "habit_checkin_reminder",
                            s.urgency(),
                            s.summary(),
                            s.subjectId(),
                            InitiativeType.NOTIFICATION))
                    .toList();
        }
    }

    // ---- 辅助方法 ----

    /** 将 HabitEntity 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> habitEntityToMap(HabitEntity entity) {
        var map = new java.util.HashMap<String, Object>();
        map.put("name", entity.name());
        map.put("frequency", entity.frequency());
        map.put("targetCount", entity.targetCount());
        map.put("currentStreak", entity.currentStreak());
        if (entity.lastCompletedAt() != null) map.put("lastCompletedAt", entity.lastCompletedAt());
        return Map.copyOf(map);
    }
}
