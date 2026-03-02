package com.lifepilot.skill.builtin.schedule;

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

import java.util.List;
import java.util.Map;

/**
 * 日程管理内置 Skill 提供者。
 *
 * <p>注册 6 个日程 CRUD 工具到 DynamicToolRegistry，
 * 提供日程管理 Skill 定义蓝图。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "schedule", order = 20)
public class ScheduleSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSkillProvider.class);

    private static final String SYSTEM_PROMPT = """
            角色：日程管理助手
            
            核心职责：
            帮助用户高效管理日程安排，通过时间冲突检测和智能提醒确保日程安排的合理性。
            
            能力范围：
            1. 日程创建
               - 必填：标题、开始时间、结束时间（ISO 8601格式）
               - 可选：地点、备注
               - 自动检测时间冲突，创建前提示用户
            2. 日程查询
               - 列表查询：按开始时间升序排列
               - 详情查看：获取单个日程的完整信息
            3. 日程更新
               - 修改标题、时间、地点、备注
               - 更新时重新检测冲突
            4. 日程管理
               - 删除日程：永久删除（需确认）
               - 冲突检测：查找指定时间段内的时间重叠
            
            交互原则：
            - 检测到时间冲突时，明确告知冲突的日程
            - 操作完成后确认结果，包括时间、地点等关键信息
            - 对于临近的日程，主动提醒用户
            - 使用清晰、简洁的语言
            
            回复风格：专业、细致、时间敏感
            """;

    private final ScheduleRepository scheduleRepository;

    public ScheduleSkillProvider(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("schedule")
                .name("日程管理")
                .description("管理日程安排，支持创建、查询、更新、删除和冲突检测")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(List.of(
                        "builtin.schedule.create",
                        "builtin.schedule.list",
                        "builtin.schedule.get",
                        "builtin.schedule.update",
                        "builtin.schedule.delete",
                        "builtin.schedule.conflicts"
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
        toolRegistry.registerBuiltinTool(buildDeleteTool());
        toolRegistry.registerBuiltinTool(buildConflictsTool());
        log.info("日程 Skill 工具注册完成: count=6");
    }

    // ---- 工具构建方法 ----

    /** 构建创建日程工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.create")
                .name("创建日程")
                .description("创建新的日程，支持设置标题、开始时间、结束时间、地点和备注")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("title", "startTime", "endTime"),
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "日程标题"),
                                "startTime", Map.of("type", "string", "description", "开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "description", "结束时间 ISO 8601"),
                                "location", Map.of("type", "string", "description", "地点"),
                                "notes", Map.of("type", "string", "description", "备注")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String title = input.getParam("title", String.class);
                        String startTime = input.getParam("startTime", String.class);
                        String endTime = input.getParam("endTime", String.class);
                        String location = input.getOptionalParam("location", String.class).orElse(null);
                        String notes = input.getOptionalParam("notes", String.class).orElse(null);

                        ScheduleItem item = new ScheduleItem(
                                null, title, startTime, endTime,
                                location, notes, null, null);
                        String id = scheduleRepository.create(item);
                        return ToolResult.success(Map.of("id", id));
                    } catch (Exception e) {
                        log.error("创建日程失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建日程失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询日程列表工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.list")
                .name("查询日程列表")
                .description("查询所有日程列表，按开始时间升序排列")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        List<ScheduleItem> items = scheduleRepository.list();
                        List<Map<String, Object>> itemMaps = items.stream()
                                .map(this::scheduleItemToMap)
                                .toList();
                        return ToolResult.success(Map.of("items", itemMaps));
                    } catch (Exception e) {
                        log.error("查询日程列表失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询日程列表失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询单个日程工具。 */
    private BuiltinTool buildGetTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.get")
                .name("查询日程详情")
                .description("根据 ID 查询单个日程的详细信息")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "日程 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        return scheduleRepository.findById(id)
                                .map(item -> ToolResult.success(scheduleItemToMap(item)))
                                .orElse(ToolResult.error("日程不存在: id=" + id));
                    } catch (Exception e) {
                        log.error("查询日程详情失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询日程详情失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建更新日程工具。 */
    private BuiltinTool buildUpdateTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.update")
                .name("更新日程")
                .description("更新日程的标题、开始时间、结束时间、地点或备注")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "日程 ID"),
                                "title", Map.of("type", "string", "description", "新标题"),
                                "startTime", Map.of("type", "string", "description", "新开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "description", "新结束时间 ISO 8601"),
                                "location", Map.of("type", "string", "description", "新地点"),
                                "notes", Map.of("type", "string", "description", "新备注")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = scheduleRepository.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("日程不存在: id=" + id);
                        }
                        ScheduleItem current = existing.get();

                        String title = input.getOptionalParam("title", String.class).orElse(current.title());
                        String startTime = input.getOptionalParam("startTime", String.class).orElse(current.startTime());
                        String endTime = input.getOptionalParam("endTime", String.class).orElse(current.endTime());
                        String location = input.getOptionalParam("location", String.class).orElse(current.location());
                        String notes = input.getOptionalParam("notes", String.class).orElse(current.notes());

                        ScheduleItem updated = new ScheduleItem(
                                id, title, startTime, endTime,
                                location, notes, current.createdAt(), current.updatedAt());
                        boolean success = scheduleRepository.update(id, updated);
                        return success
                                ? ToolResult.success(Map.of("updated", true))
                                : ToolResult.error("更新日程失败: id=" + id);
                    } catch (Exception e) {
                        log.error("更新日程失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新日程失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除日程工具。 */
    private BuiltinTool buildDeleteTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.delete")
                .name("删除日程")
                .description("根据 ID 删除日程")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "日程 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        boolean success = scheduleRepository.delete(id);
                        return success
                                ? ToolResult.success(Map.of("deleted", true))
                                : ToolResult.error("日程不存在: id=" + id);
                    } catch (Exception e) {
                        log.error("删除日程失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除日程失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建冲突检测工具。 */
    private BuiltinTool buildConflictsTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.conflicts")
                .name("检测日程冲突")
                .description("查找与指定时间段存在时间重叠的日程")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("startTime", "endTime"),
                        "properties", Map.of(
                                "startTime", Map.of("type", "string", "description", "查询开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "description", "查询结束时间 ISO 8601")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String startTime = input.getParam("startTime", String.class);
                        String endTime = input.getParam("endTime", String.class);
                        List<ScheduleItem> conflicts = scheduleRepository.findConflicts(startTime, endTime);
                        List<Map<String, Object>> itemMaps = conflicts.stream()
                                .map(this::scheduleItemToMap)
                                .toList();
                        return ToolResult.success(Map.of("conflicts", itemMaps, "count", itemMaps.size()));
                    } catch (Exception e) {
                        log.error("检测日程冲突失败: {}", e.getMessage(), e);
                        return ToolResult.error("检测日程冲突失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 辅助方法 ----

    /** 将 ScheduleItem 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> scheduleItemToMap(ScheduleItem item) {
        var map = new java.util.HashMap<String, Object>();
        map.put("id", item.id());
        map.put("title", item.title());
        map.put("startTime", item.startTime());
        map.put("endTime", item.endTime());
        if (item.location() != null) map.put("location", item.location());
        if (item.notes() != null) map.put("notes", item.notes());
        map.put("createdAt", item.createdAt());
        map.put("updatedAt", item.updatedAt());
        return Map.copyOf(map);
    }
}
