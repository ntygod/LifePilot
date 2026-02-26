package com.lifepilot.sync.engine;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.sync.model.LocalChangeSet;
import com.lifepilot.sync.model.LocalChangeSet.LocalEntity;
import com.lifepilot.sync.model.SyncProfile;
import com.lifepilot.sync.model.SyncRecord;
import com.lifepilot.sync.repository.SyncRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地变更检测器 — 检测本地数据自上次同步以来的新增、修改、删除变更。
 *
 * <p>检测逻辑：
 * <ul>
 *   <li>新增：本地实体无对应 SyncRecord</li>
 *   <li>修改：实体 updatedAt &gt; SyncRecord.lastSyncAt</li>
 *   <li>删除：SyncRecord 存在但本地实体已不存在</li>
 * </ul>
 *
 * <p>检测范围由 {@link SyncProfile#dataTypeFilterJson()} 控制，
 * 仅检测配置中启用的数据类型。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class ChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(ChangeDetector.class);

    private static final String TYPE_TODO = "TodoItem";
    private static final String TYPE_SCHEDULE = "ScheduleItem";
    private static final String TYPE_HABIT = "HabitItem";

    private final TodoRepository todoRepository;
    private final ScheduleRepository scheduleRepository;
    private final HabitRepository habitRepository;
    private final SyncRecordRepository syncRecordRepository;

    public ChangeDetector(TodoRepository todoRepository,
                          ScheduleRepository scheduleRepository,
                          HabitRepository habitRepository,
                          SyncRecordRepository syncRecordRepository) {
        this.todoRepository = todoRepository;
        this.scheduleRepository = scheduleRepository;
        this.habitRepository = habitRepository;
        this.syncRecordRepository = syncRecordRepository;
    }

    /**
     * 检测本地变更。
     *
     * <p>按 {@link SyncProfile#dataTypeFilterJson()} 过滤检测范围，
     * 对每种启用的数据类型分别检测新增、修改、删除。</p>
     *
     * @param profile 同步配置
     * @return 本地变更集
     */
    public LocalChangeSet detectLocalChanges(SyncProfile profile) {
        Set<String> enabledTypes = parseDataTypeFilter(profile.dataTypeFilterJson());
        log.debug("开始检测本地变更: profileId={}, 启用类型={}", profile.id(), enabledTypes);

        List<LocalEntity> created = new ArrayList<>();
        List<LocalEntity> updated = new ArrayList<>();
        List<LocalEntity> deleted = new ArrayList<>();

        if (enabledTypes.contains(TYPE_TODO)) {
            detectChangesForType(profile.id(), TYPE_TODO,
                    todoRepository.list(null, null),
                    TodoItem::id, TodoItem::updatedAt,
                    created, updated, deleted);
        }

        if (enabledTypes.contains(TYPE_SCHEDULE)) {
            detectChangesForType(profile.id(), TYPE_SCHEDULE,
                    scheduleRepository.list(),
                    ScheduleItem::id, ScheduleItem::updatedAt,
                    created, updated, deleted);
        }

        if (enabledTypes.contains(TYPE_HABIT)) {
            detectChangesForType(profile.id(), TYPE_HABIT,
                    habitRepository.list(),
                    HabitItem::id, HabitItem::updatedAt,
                    created, updated, deleted);
        }

        log.info("本地变更检测完成: profileId={}, 新增={}, 修改={}, 删除={}",
                profile.id(), created.size(), updated.size(), deleted.size());

        return new LocalChangeSet(created, updated, deleted);
    }

    /**
     * 对单一数据类型执行变更检测。
     *
     * @param profileId  同步配置 ID
     * @param entityType 实体类型名称
     * @param entities   当前本地实体列表
     * @param idExtractor      提取实体 ID 的函数
     * @param updatedAtExtractor 提取实体 updatedAt 的函数
     * @param created    新增列表（输出参数）
     * @param updated    修改列表（输出参数）
     * @param deleted    删除列表（输出参数）
     */
    private <T> void detectChangesForType(String profileId,
                                          String entityType,
                                          List<T> entities,
                                          Function<T, String> idExtractor,
                                          Function<T, String> updatedAtExtractor,
                                          List<LocalEntity> created,
                                          List<LocalEntity> updated,
                                          List<LocalEntity> deleted) {
        // 获取该 profile + 类型下的所有 SyncRecord，构建 localEntityId → SyncRecord 映射
        List<SyncRecord> syncRecords = syncRecordRepository.findByProfileId(profileId);
        Map<String, SyncRecord> recordMap = syncRecords.stream()
                .filter(r -> entityType.equals(r.localEntityType()))
                .collect(Collectors.toMap(SyncRecord::localEntityId, Function.identity()));

        // 记录本地存在的实体 ID 集合，用于后续检测删除
        Set<String> localEntityIds = new HashSet<>();

        for (T entity : entities) {
            String entityId = idExtractor.apply(entity);
            localEntityIds.add(entityId);

            SyncRecord syncRecord = recordMap.get(entityId);
            if (syncRecord == null) {
                // 无对应 SyncRecord → 新增
                created.add(new LocalEntity(entityId, entityType, entity));
            } else {
                // 有 SyncRecord → 比较 updatedAt 与 lastSyncAt
                String entityUpdatedAt = updatedAtExtractor.apply(entity);
                if (entityUpdatedAt != null && entityUpdatedAt.compareTo(syncRecord.lastSyncAt()) > 0) {
                    updated.add(new LocalEntity(entityId, entityType, entity));
                }
            }
        }

        // 检测删除：SyncRecord 存在但本地实体已不存在
        for (SyncRecord record : recordMap.values()) {
            if (!localEntityIds.contains(record.localEntityId())) {
                deleted.add(new LocalEntity(record.localEntityId(), entityType, null));
            }
        }
    }

    /**
     * 解析 dataTypeFilterJson 为数据类型集合。
     *
     * <p>JSON 格式为简单数组：["TodoItem", "ScheduleItem"]。
     * 如果为 null 或空，返回所有支持的类型。</p>
     *
     * @param dataTypeFilterJson JSON 数组字符串
     * @return 启用的数据类型集合
     */
    Set<String> parseDataTypeFilter(String dataTypeFilterJson) {
        if (dataTypeFilterJson == null || dataTypeFilterJson.isBlank()) {
            // 无过滤器 → 检测所有类型
            return Set.of(TYPE_TODO, TYPE_SCHEDULE, TYPE_HABIT);
        }

        // 简单 JSON 数组解析：去掉首尾 []，按逗号分割，去掉引号和空白
        String content = dataTypeFilterJson.trim();
        if (content.startsWith("[")) {
            content = content.substring(1);
        }
        if (content.endsWith("]")) {
            content = content.substring(0, content.length() - 1);
        }

        if (content.isBlank()) {
            return Set.of(TYPE_TODO, TYPE_SCHEDULE, TYPE_HABIT);
        }

        Set<String> types = new HashSet<>();
        for (String part : content.split(",")) {
            String type = part.trim().replace("\"", "");
            if (!type.isBlank()) {
                types.add(type);
            }
        }

        return types.isEmpty() ? Set.of(TYPE_TODO, TYPE_SCHEDULE, TYPE_HABIT) : Set.copyOf(types);
    }
}
