package com.lifepilot.skill.builtin.schedule;

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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日程管理内置 Skill 提供者。
 *
 * <p>注册 6 个日程 CRUD 工具到 DynamicToolRegistry，
 * 提供日程管理 Skill 定义蓝图。实现 {@link ProactiveSkillProvider}，
 * 提供日程提醒信号源和候选提供者。</p>
 *
 * <p>存储层通过 {@link DataStoreCrudAdapter} 委托给 DataStore，
 * 数据以 JSON 格式存储在"日程"Collection 中。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@BuiltinSkill(id = "schedule", order = 20)
public class ScheduleSkillProvider implements ProactiveSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSkillProvider.class);

    private final DataStoreCrudAdapter<ScheduleEntity> scheduleAdapter;
    private final PromptRegistry promptRegistry;

    public ScheduleSkillProvider(DataStoreManager dataStoreManager,
                                 ObjectMapper objectMapper,
                                 PromptRegistry promptRegistry) {
        this.scheduleAdapter = new DataStoreCrudAdapter<>(dataStoreManager, objectMapper,
                new CrudAdapterConfig<>(
                        "schedule",
                        "日程",
                        CollectionType.DOCUMENT,
                        ScheduleEntity.class,
                        List.of(
                                new PropertyDefinition("title", PropertyType.TEXT, true, "日程标题"),
                                new PropertyDefinition("startTime", PropertyType.DATE, true, "开始时间 ISO 8601"),
                                new PropertyDefinition("endTime", PropertyType.DATE, true, "结束时间 ISO 8601"),
                                new PropertyDefinition("recurrence", PropertyType.SELECT, false, "重复规则: daily/weekly/monthly"),
                                new PropertyDefinition("location", PropertyType.TEXT, false, "地点")
                        ),
                        "日程管理"
                ));
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("schedule")
                .name("日程管理")
                .description("管理日程安排，支持创建、查询、更新、删除和冲突检测")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/schedule"))
                .suggestedTools(List.of(
                        "builtin.schedule.create",
                        "builtin.schedule.list",
                        "builtin.schedule.get",
                        "builtin.schedule.update",
                        "builtin.schedule.delete",
                        "builtin.schedule.conflicts"
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
        toolRegistry.registerBuiltinTool(buildDeleteTool());
        toolRegistry.registerBuiltinTool(buildConflictsTool());
        log.info("日程 Skill 工具注册完成: count=6");
    }

    // ---- ProactiveSkillProvider 实现 ----

    @Override
    public List<SignalSource> signalSources() {
        return List.of(new ScheduleSignalSource());
    }

    @Override
    public List<CandidateProvider> candidateProviders() {
        return List.of(new ScheduleCandidateProvider());
    }

    // ---- 工具构建方法 ----

    /** 构建创建日程工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.create")
                .name("创建日程")
                .description("创建新的日程，支持设置标题、开始时间、结束时间、地点和备注")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("title", "startTime", "endTime"),
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "日程标题"),
                                "startTime", Map.of("type", "string", "format", "date-time", "description", "开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "format", "date-time", "description", "结束时间 ISO 8601"),
                                "recurrence", Map.of("type", "string", "description", "重复规则: daily/weekly/monthly"),
                                "location", Map.of("type", "string", "description", "地点"),
                                "description", Map.of("type", "string", "description", "备注")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String title = input.getParam("title", String.class);
                        String startTime = input.getParam("startTime", String.class);
                        String endTime = input.getParam("endTime", String.class);
                        String recurrence = input.getOptionalParam("recurrence", String.class).orElse(null);
                        String location = input.getOptionalParam("location", String.class).orElse(null);
                        String description = input.getOptionalParam("description", String.class).orElse(null);

                        var entity = new ScheduleEntity(title, startTime, endTime, recurrence, location, description);
                        return scheduleAdapter.create(entity);
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
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        List<ScheduleEntity> entities = scheduleAdapter.list(
                                null, "startTime", null, 0, 100);
                        List<Map<String, Object>> itemMaps = entities.stream()
                                .map(this::scheduleEntityToMap)
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
                .category(ToolCategory.STORAGE)
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
                        return scheduleAdapter.findById(id)
                                .map(entity -> ToolResult.success(scheduleEntityToMap(entity)))
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
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("id"),
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "日程 ID"),
                                "title", Map.of("type", "string", "description", "新标题"),
                                "startTime", Map.of("type", "string", "format", "date-time", "description", "新开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "format", "date-time", "description", "新结束时间 ISO 8601"),
                                "recurrence", Map.of("type", "string", "description", "新重复规则: daily/weekly/monthly"),
                                "location", Map.of("type", "string", "description", "新地点"),
                                "description", Map.of("type", "string", "description", "新备注")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String id = input.getParam("id", String.class);
                        var existing = scheduleAdapter.findById(id);
                        if (existing.isEmpty()) {
                            return ToolResult.error("日程不存在: id=" + id);
                        }
                        ScheduleEntity current = existing.get();

                        String title = input.getOptionalParam("title", String.class).orElse(current.title());
                        String startTime = input.getOptionalParam("startTime", String.class).orElse(current.startTime());
                        String endTime = input.getOptionalParam("endTime", String.class).orElse(current.endTime());
                        String recurrence = input.getOptionalParam("recurrence", String.class).orElse(current.recurrence());
                        String location = input.getOptionalParam("location", String.class).orElse(current.location());
                        String description = input.getOptionalParam("description", String.class).orElse(current.description());

                        var updated = new ScheduleEntity(title, startTime, endTime, recurrence, location, description);
                        return scheduleAdapter.update(id, updated);
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
                .category(ToolCategory.STORAGE)
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
                        return scheduleAdapter.delete(id);
                    } catch (Exception e) {
                        log.error("删除日程失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除日程失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建冲突检测工具 — 列出所有日程并在内存中过滤时间重叠。 */
    private BuiltinTool buildConflictsTool() {
        return BuiltinTool.builder()
                .id("builtin.schedule.conflicts")
                .name("检测日程冲突")
                .description("查找与指定时间段存在时间重叠的日程")
                .category(ToolCategory.STORAGE)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("startTime", "endTime"),
                        "properties", Map.of(
                                "startTime", Map.of("type", "string", "format", "date-time", "description", "查询开始时间 ISO 8601"),
                                "endTime", Map.of("type", "string", "format", "date-time", "description", "查询结束时间 ISO 8601")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String queryStart = input.getParam("startTime", String.class);
                        String queryEnd = input.getParam("endTime", String.class);

                        // DataStoreCrudAdapter 无 findConflicts，列出所有日程后内存过滤
                        List<ScheduleEntity> allSchedules = scheduleAdapter.list(
                                null, null, null, 0, 1000);
                        List<Map<String, Object>> conflicts = allSchedules.stream()
                                .filter(s -> isOverlapping(s.startTime(), s.endTime(), queryStart, queryEnd))
                                .map(this::scheduleEntityToMap)
                                .toList();
                        return ToolResult.success(Map.of("conflicts", conflicts, "count", conflicts.size()));
                    } catch (Exception e) {
                        log.error("检测日程冲突失败: {}", e.getMessage(), e);
                        return ToolResult.error("检测日程冲突失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- 主动推理内部类 ----

    /**
     * 日程提醒信号源 — 收集 60 分钟内即将开始的日程信号。
     *
     * <p>根据距开始时间的剩余时长计算紧急度：
     * &lt; 15min → HIGH，&lt; 30min → MEDIUM，其余 → LOW。</p>
     */
    private class ScheduleSignalSource implements SignalSource {

        @Override
        public String id() {
            return "schedule-signal";
        }

        @Override
        public List<Signal> collect() {
            List<ScheduleEntity> schedules = scheduleAdapter.list(null, null, null, 0, 1000);
            Instant now = Instant.now();
            Instant horizon = now.plus(Duration.ofMinutes(60));
            List<Signal> signals = new ArrayList<>();

            for (ScheduleEntity schedule : schedules) {
                if (schedule.startTime() == null) {
                    continue;
                }
                try {
                    Instant startInstant = Instant.parse(schedule.startTime());
                    // 仅收集 60 分钟内即将开始且尚未过去的日程
                    if (startInstant.isAfter(now) && !startInstant.isAfter(horizon)) {
                        Duration remaining = Duration.between(now, startInstant);
                        Urgency urgency = remaining.toMinutes() < 15 ? Urgency.HIGH
                                : remaining.toMinutes() < 30 ? Urgency.MEDIUM
                                : Urgency.LOW;

                        signals.add(Signal.builder()
                                .typeId("schedule_reminder")
                                .urgency(urgency)
                                .summary("日程「%s」将于 %s 开始".formatted(schedule.title(), schedule.startTime()))
                                .sourceId("schedule-signal")
                                .subjectId(schedule.title())
                                .metadata(Map.of(
                                        "title", schedule.title(),
                                        "startTime", schedule.startTime()))
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("解析日程开始时间失败: title={}, startTime={}", schedule.title(), schedule.startTime(), e);
                }
            }

            log.debug("日程信号收集完成: count={}", signals.size());
            return List.copyOf(signals);
        }
    }

    /**
     * 日程候选提供者 — 将 schedule_reminder 信号映射为 NOTIFICATION 候选。
     */
    private class ScheduleCandidateProvider implements CandidateProvider {

        @Override
        public String id() {
            return "schedule-candidate";
        }

        @Override
        public List<ProactiveCandidate> evaluate(SignalBundle signals) {
            return signals.signals().stream()
                    .filter(s -> "schedule_reminder".equals(s.typeId()))
                    .filter(s -> "schedule-signal".equals(s.sourceId()))
                    .map(s -> new ProactiveCandidate(
                            "schedule_reminder",
                            s.urgency(),
                            s.summary(),
                            s.subjectId(),
                            InitiativeType.NOTIFICATION))
                    .toList();
        }
    }

    // ---- 辅助方法 ----

    /**
     * 判断两个时间段是否重叠。
     *
     * <p>重叠条件：schedule.startTime &lt; queryEnd AND schedule.endTime &gt; queryStart。</p>
     */
    private boolean isOverlapping(String schedStart, String schedEnd, String queryStart, String queryEnd) {
        try {
            Instant ss = Instant.parse(schedStart);
            Instant se = Instant.parse(schedEnd);
            Instant qs = Instant.parse(queryStart);
            Instant qe = Instant.parse(queryEnd);
            return ss.isBefore(qe) && se.isAfter(qs);
        } catch (Exception e) {
            log.warn("解析日程时间失败，跳过冲突检测: schedStart={}, schedEnd={}", schedStart, schedEnd);
            return false;
        }
    }

    /** 将 ScheduleEntity 转换为 Map 用于 ToolResult。 */
    private Map<String, Object> scheduleEntityToMap(ScheduleEntity entity) {
        var map = new java.util.HashMap<String, Object>();
        map.put("title", entity.title());
        map.put("startTime", entity.startTime());
        map.put("endTime", entity.endTime());
        if (entity.recurrence() != null) map.put("recurrence", entity.recurrence());
        if (entity.location() != null) map.put("location", entity.location());
        if (entity.description() != null) map.put("description", entity.description());
        return Map.copyOf(map);
    }
}
