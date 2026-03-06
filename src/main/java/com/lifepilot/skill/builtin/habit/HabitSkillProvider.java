package com.lifepilot.skill.builtin.habit;

import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 习惯养成内置 Skill 提供者。
 *
 * <p>注册 7 个习惯管理工具到 DynamicToolRegistry，
 * 提供习惯养成 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "habit", order = 30)
public class HabitSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(HabitSkillProvider.class);

    private final HabitRepository habitRepository;
    private final PromptRegistry promptRegistry;

    public HabitSkillProvider(HabitRepository habitRepository, PromptRegistry promptRegistry) {
        this.habitRepository = habitRepository;
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
                .systemPrompt(promptRegistry.render("skill/habit"))
                .allowedTools(List.of(
                        "builtin.habit.create",
                        "builtin.habit.list",
                        "builtin.habit.get",
                        "builtin.habit.update",
                        "builtin.habit.checkin",
                        "builtin.habit.streak",
                        "builtin.habit.completion-rate"
                ))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.LIGHTWEIGHT)
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

    // ---- 工具构建方法 ----

    /** 构建创建习惯工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.create")
                .name("创建习惯")
                .description("创建新的习惯，支持设置名称、频率和目标打卡时间")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "frequency"),
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "习惯名称"),
                                "frequency", Map.of("type", "string", "description", "频率: DAILY/WEEKLY"),
                                "targetTime", Map.of("type", "string", "format", "time", "description", "目标打卡时间 HH:mm")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String name = input.getParam("name", String.class);
                        String frequencyStr = input.getParam("frequency", String.class);
                        String targetTime = input.getOptionalParam("targetTime", String.class).orElse(null);

                        HabitItem.Frequency frequency = HabitItem.Frequency.valueOf(frequencyStr.toUpperCase());
                        HabitItem item = new HabitItem(null, name, frequency, targetTime, 0, null, null);
                        String id = habitRepository.create(item);
                        return ToolResult.success(Map.of("id", id));
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
                .description("查询所有习惯列表，按创建时间降序排列")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        List<HabitItem> items = habitRepository.list();
                        List<Map<String, Object>> itemMaps = items.stream()
                                .map(this::habitItemToMap)
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
                        return habitRepository.findById(id)
                                .map(item -> ToolResult.success(habitItemToMap(item)))
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
                .description("更新习惯的名称、频率或目标打卡时间")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "习惯 ID"),
                                "name", Map.of("type", "string", "description", "新名称"),
                                "frequency", Map.of("type", "string", "description", "新频率: DAILY/WEEKLY"),
                                "targetTime", Map.of("type", "string", "format", "time", "description", "新目标打卡时间 HH:mm")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = habitRepository.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("习惯不存在: id=" + id);
                        }
                        HabitItem current = existing.get();

                        String name = input.getOptionalParam("name", String.class).orElse(current.name());
                        String frequencyStr = input.getOptionalParam("frequency", String.class).orElse(current.frequency().name());
                        String targetTime = input.getOptionalParam("targetTime", String.class).orElse(current.targetTime());

                        HabitItem.Frequency frequency = HabitItem.Frequency.valueOf(frequencyStr.toUpperCase());
                        HabitItem updated = new HabitItem(
                                id, name, frequency, targetTime,
                                current.currentStreak(), current.createdAt(), current.updatedAt());
                        boolean success = habitRepository.update(id, updated);
                        return success
                                ? ToolResult.success(Map.of("updated", true))
                                : ToolResult.error("更新习惯失败: id=" + id);
                    } catch (Exception e) {
                        log.error("更新习惯失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新习惯失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建习惯打卡工具。 */
    private BuiltinTool buildCheckinTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.checkin")
                .name("习惯打卡")
                .description("为指定习惯记录一次打卡，自动更新连续打卡天数")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("habitId"),
                        "properties", Map.of(
                                "habitId", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String habitId = input.getParam("habitId", String.class);
                        String logId = habitRepository.checkin(habitId);
                        return ToolResult.success(Map.of("logId", logId));
                    } catch (Exception e) {
                        log.error("习惯打卡失败: {}", e.getMessage(), e);
                        return ToolResult.error("习惯打卡失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询连续打卡天数工具。 */
    private BuiltinTool buildStreakTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.streak")
                .name("查询连续打卡天数")
                .description("查询指定习惯的当前连续打卡天数")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("habitId"),
                        "properties", Map.of(
                                "habitId", Map.of("type", "string", "description", "习惯 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String habitId = input.getParam("habitId", String.class);
                        int streak = habitRepository.calculateStreak(habitId);
                        return ToolResult.success(Map.of("habitId", habitId, "streak", streak));
                    } catch (Exception e) {
                        log.error("查询连续打卡天数失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询连续打卡天数失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建计算完成率工具。 */
    private BuiltinTool buildCompletionRateTool() {
        return BuiltinTool.builder()
                .id("builtin.habit.completion-rate")
                .name("计算完成率")
                .description("计算指定习惯在指定时间范围内的完成率")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("habitId", "from", "to"),
                        "properties", Map.of(
                                "habitId", Map.of("type", "string", "description", "习惯 ID"),
                                "from", Map.of("type", "string", "description", "起始时间 ISO 8601"),
                                "to", Map.of("type", "string", "description", "结束时间 ISO 8601")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String habitId = input.getParam("habitId", String.class);
                        String from = input.getParam("from", String.class);
                        String to = input.getParam("to", String.class);
                        double rate = habitRepository.calculateCompletionRate(
                                habitId, Instant.parse(from), Instant.parse(to));
                        return ToolResult.success(Map.of("habitId", habitId, "completionRate", rate));
                    } catch (Exception e) {
                        log.error("计算完成率失败: {}", e.getMessage(), e);
                        return ToolResult.error("计算完成率失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将 HabitItem 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> habitItemToMap(HabitItem item) {
        var map = new java.util.HashMap<String, Object>();
        map.put("id", item.id());
        map.put("name", item.name());
        map.put("frequency", item.frequency().name());
        if (item.targetTime() != null) map.put("targetTime", item.targetTime());
        map.put("currentStreak", item.currentStreak());
        map.put("createdAt", item.createdAt());
        map.put("updatedAt", item.updatedAt());
        return Map.copyOf(map);
    }
}
