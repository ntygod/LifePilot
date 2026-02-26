package com.lifepilot.sync.engine;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.sync.model.LocalChangeSet;
import com.lifepilot.sync.model.SyncProfile;
import com.lifepilot.sync.model.SyncRecord;
import com.lifepilot.sync.model.ConflictPolicy;
import com.lifepilot.sync.model.SyncDirection;
import com.lifepilot.sync.repository.SyncRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ChangeDetector 单元测试。
 *
 * <p>使用 Mockito Mock 仓储层，验证本地变更检测逻辑的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class ChangeDetectorTest {

    @Mock
    private TodoRepository todoRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private HabitRepository habitRepository;
    @Mock
    private SyncRecordRepository syncRecordRepository;

    private ChangeDetector changeDetector;
    private static final String PROFILE_ID = "profile-001";
    private static final String NOW = Instant.now().toString();

    @BeforeEach
    void setUp() {
        changeDetector = new ChangeDetector(todoRepository, scheduleRepository, habitRepository, syncRecordRepository);
    }

    // ---- 辅助方法 ----

    private SyncProfile buildProfile(String dataTypeFilterJson) {
        return SyncProfile.builder()
                .id(PROFILE_ID)
                .name("测试配置")
                .connectorType("todoist")
                .connectionParamsJson("{}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(true)
                .dataTypeFilterJson(dataTypeFilterJson)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private TodoItem buildTodo(String id, String updatedAt) {
        return new TodoItem(id, "待办-" + id, null, TodoItem.Priority.MEDIUM,
                TodoItem.Status.PENDING, null, null, NOW, updatedAt);
    }

    private ScheduleItem buildSchedule(String id, String updatedAt) {
        return new ScheduleItem(id, "日程-" + id, NOW, NOW, null, null, NOW, updatedAt);
    }

    private HabitItem buildHabit(String id, String updatedAt) {
        return new HabitItem(id, "习惯-" + id, HabitItem.Frequency.DAILY, null, 0, NOW, updatedAt);
    }

    private SyncRecord buildSyncRecord(String localEntityType, String localEntityId, String lastSyncAt) {
        return new SyncRecord(
                "sr-" + localEntityId, PROFILE_ID, localEntityType, localEntityId,
                "remote-" + localEntityId, null, null, lastSyncAt, NOW, NOW);
    }

    // ---- 新增检测 ----

    @Test
    void 新增实体_无SyncRecord_检测为created() {
        var profile = buildProfile("[\"TodoItem\"]");
        var todo = buildTodo("todo-1", NOW);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).hasSize(1);
        assertThat(result.created().getFirst().localId()).isEqualTo("todo-1");
        assertThat(result.created().getFirst().entityType()).isEqualTo("TodoItem");
        assertThat(result.created().getFirst().entity()).isEqualTo(todo);
        assertThat(result.updated()).isEmpty();
        assertThat(result.deleted()).isEmpty();
    }

    // ---- 修改检测 ----

    @Test
    void 修改实体_updatedAt晚于lastSyncAt_检测为updated() {
        var profile = buildProfile("[\"TodoItem\"]");
        var lastSync = "2026-02-25T10:00:00Z";
        var entityUpdate = "2026-02-26T08:00:00Z";
        var todo = buildTodo("todo-1", entityUpdate);
        var syncRecord = buildSyncRecord("TodoItem", "todo-1", lastSync);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.updated()).hasSize(1);
        assertThat(result.updated().getFirst().localId()).isEqualTo("todo-1");
        assertThat(result.created()).isEmpty();
        assertThat(result.deleted()).isEmpty();
    }

    // ---- 未修改实体 ----

    @Test
    void 未修改实体_updatedAt等于lastSyncAt_不在任何列表中() {
        var profile = buildProfile("[\"TodoItem\"]");
        var sameTime = "2026-02-25T10:00:00Z";
        var todo = buildTodo("todo-1", sameTime);
        var syncRecord = buildSyncRecord("TodoItem", "todo-1", sameTime);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.deleted()).isEmpty();
    }

    // ---- 删除检测 ----

    @Test
    void 删除实体_SyncRecord存在但实体不存在_检测为deleted() {
        var profile = buildProfile("[\"TodoItem\"]");
        var syncRecord = buildSyncRecord("TodoItem", "todo-deleted", NOW);

        when(todoRepository.list(null, null)).thenReturn(List.of());
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.deleted()).hasSize(1);
        assertThat(result.deleted().getFirst().localId()).isEqualTo("todo-deleted");
        assertThat(result.deleted().getFirst().entityType()).isEqualTo("TodoItem");
        assertThat(result.deleted().getFirst().entity()).isNull();
        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
    }

    // ---- 数据类型过滤 ----

    @Test
    void dataTypeFilter_仅TodoItem_不检测其他类型() {
        var profile = buildProfile("[\"TodoItem\"]");
        var todo = buildTodo("todo-1", NOW);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());
        // scheduleRepository 和 habitRepository 不应被调用

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).hasSize(1);
        assertThat(result.created().getFirst().entityType()).isEqualTo("TodoItem");
    }

    @Test
    void dataTypeFilter_空或null_检测所有类型() {
        var profile = buildProfile(null);
        var todo = buildTodo("todo-1", NOW);
        var schedule = buildSchedule("sch-1", NOW);
        var habit = buildHabit("hab-1", NOW);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(scheduleRepository.list()).thenReturn(List.of(schedule));
        when(habitRepository.list()).thenReturn(List.of(habit));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).hasSize(3);
        assertThat(result.created().stream().map(LocalChangeSet.LocalEntity::entityType).toList())
                .containsExactlyInAnyOrder("TodoItem", "ScheduleItem", "HabitItem");
    }

    @Test
    void dataTypeFilter_空数组_检测所有类型() {
        var profile = buildProfile("[]");
        var todo = buildTodo("todo-1", NOW);
        var schedule = buildSchedule("sch-1", NOW);
        var habit = buildHabit("hab-1", NOW);

        when(todoRepository.list(null, null)).thenReturn(List.of(todo));
        when(scheduleRepository.list()).thenReturn(List.of(schedule));
        when(habitRepository.list()).thenReturn(List.of(habit));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).hasSize(3);
    }

    // ---- 空仓储 ----

    @Test
    void 空仓储_返回空LocalChangeSet() {
        var profile = buildProfile(null);

        when(todoRepository.list(null, null)).thenReturn(List.of());
        when(scheduleRepository.list()).thenReturn(List.of());
        when(habitRepository.list()).thenReturn(List.of());
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.deleted()).isEmpty();
    }

    // ---- 混合场景 ----

    @Test
    void 混合场景_同时检测新增修改删除() {
        var profile = buildProfile("[\"TodoItem\"]");
        var lastSync = "2026-02-25T10:00:00Z";

        // todo-new: 新增（无 SyncRecord）
        var todoNew = buildTodo("todo-new", NOW);
        // todo-mod: 修改（updatedAt > lastSyncAt）
        var todoMod = buildTodo("todo-mod", "2026-02-26T08:00:00Z");
        // todo-unchanged: 未修改
        var todoUnchanged = buildTodo("todo-unchanged", lastSync);
        // todo-deleted: 已删除（SyncRecord 存在但实体不存在）

        var srMod = buildSyncRecord("TodoItem", "todo-mod", lastSync);
        var srUnchanged = buildSyncRecord("TodoItem", "todo-unchanged", lastSync);
        var srDeleted = buildSyncRecord("TodoItem", "todo-deleted", lastSync);

        when(todoRepository.list(null, null)).thenReturn(List.of(todoNew, todoMod, todoUnchanged));
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(srMod, srUnchanged, srDeleted));

        LocalChangeSet result = changeDetector.detectLocalChanges(profile);

        assertThat(result.created()).hasSize(1);
        assertThat(result.created().getFirst().localId()).isEqualTo("todo-new");

        assertThat(result.updated()).hasSize(1);
        assertThat(result.updated().getFirst().localId()).isEqualTo("todo-mod");

        assertThat(result.deleted()).hasSize(1);
        assertThat(result.deleted().getFirst().localId()).isEqualTo("todo-deleted");
    }

    // ---- parseDataTypeFilter 辅助方法测试 ----

    @Test
    void parseDataTypeFilter_正常JSON数组() {
        var types = changeDetector.parseDataTypeFilter("[\"TodoItem\", \"ScheduleItem\"]");
        assertThat(types).containsExactlyInAnyOrder("TodoItem", "ScheduleItem");
    }

    @Test
    void parseDataTypeFilter_null返回所有类型() {
        var types = changeDetector.parseDataTypeFilter(null);
        assertThat(types).containsExactlyInAnyOrder("TodoItem", "ScheduleItem", "HabitItem");
    }

    @Test
    void parseDataTypeFilter_空字符串返回所有类型() {
        var types = changeDetector.parseDataTypeFilter("");
        assertThat(types).containsExactlyInAnyOrder("TodoItem", "ScheduleItem", "HabitItem");
    }

    @Test
    void parseDataTypeFilter_单个类型() {
        var types = changeDetector.parseDataTypeFilter("[\"HabitItem\"]");
        assertThat(types).containsExactly("HabitItem");
    }
}
