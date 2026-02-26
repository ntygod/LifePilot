package com.lifepilot.sync.scheduler;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.engine.SyncEngine;
import com.lifepilot.sync.model.*;
import com.lifepilot.sync.repository.SyncProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SyncScheduler 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class SyncSchedulerTest {

    private SyncEngine syncEngine;
    private SyncProfileRepository profileRepository;
    private SyncProperties properties;
    private ScheduledExecutorService scheduler;
    private SyncScheduler syncScheduler;

    private static final String PROFILE_ID = "profile-1";
    private static final String PROFILE_ID_2 = "profile-2";

    @BeforeEach
    void setUp() {
        syncEngine = mock(SyncEngine.class);
        profileRepository = mock(SyncProfileRepository.class);
        properties = new SyncProperties();
        properties.setEventSyncMinInterval(60);
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        syncScheduler = new SyncScheduler(syncEngine, profileRepository, properties, scheduler);
    }

    // ---- 辅助方法 ----

    private SyncProfile buildProfile(String id, boolean enabled) {
        return buildProfile(id, enabled, "0 */15 * * * *", "[\"TodoItem\"]");
    }

    private SyncProfile buildProfile(String id, boolean enabled, String cron, String dataTypeFilter) {
        String now = Instant.now().toString();
        return SyncProfile.builder()
                .id(id)
                .name("测试同步-" + id)
                .connectorType("todoist")
                .connectionParamsJson("{}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression(cron)
                .enabled(enabled)
                .dataTypeFilterJson(dataTypeFilter)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private SyncResult buildSuccessResult(String profileId) {
        return new SyncResult(profileId, 1, 1, 0, 0,
                SyncStatus.SUCCESS, null, Instant.now().toString());
    }

    // ---- 启动调度测试 ----

    @Test
    void 启动后_所有启用Profile已调度() {
        var profile1 = buildProfile(PROFILE_ID, true);
        var profile2 = buildProfile(PROFILE_ID_2, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile1, profile2));

        syncScheduler.start();

        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(2);
        assertThat(syncScheduler.getScheduledProfileIds()).containsExactlyInAnyOrder(PROFILE_ID, PROFILE_ID_2);
    }

    @Test
    void 禁用Profile_不调度() {
        // findAllEnabled 只返回启用的 profile，禁用的不会出现
        var enabledProfile = buildProfile(PROFILE_ID, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(enabledProfile));

        syncScheduler.start();

        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(1);
        assertThat(syncScheduler.getScheduledProfileIds()).containsExactly(PROFILE_ID);
    }

    // ---- 事件触发同步防抖测试 ----

    @Test
    void 事件触发同步_防抖间隔内跳过() {
        var profile = buildProfile(PROFILE_ID, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile));
        when(syncEngine.sync(any())).thenReturn(buildSuccessResult(PROFILE_ID));

        // 设置上次同步时间为刚刚（在防抖间隔内）
        syncScheduler.setLastEventSyncTime(PROFILE_ID, Instant.now());

        syncScheduler.triggerEventSync("TodoItem");

        // 等待一小段时间确保异步任务有机会执行
        sleepQuietly(200);

        // 防抖间隔内不应触发同步
        verify(syncEngine, never()).sync(any());
    }

    @Test
    void 事件触发同步_超过防抖间隔_正常执行() {
        var profile = buildProfile(PROFILE_ID, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile));
        when(syncEngine.sync(any())).thenReturn(buildSuccessResult(PROFILE_ID));

        // 设置上次同步时间为很久以前（超过防抖间隔）
        syncScheduler.setLastEventSyncTime(PROFILE_ID,
                Instant.now().minusSeconds(properties.getEventSyncMinInterval() + 10));

        syncScheduler.triggerEventSync("TodoItem");

        // 等待异步执行
        sleepQuietly(500);

        verify(syncEngine, times(1)).sync(profile);
    }

    // ---- 重叠同步测试 ----

    @Test
    void 重叠同步_跳过新请求() {
        var profile = buildProfile(PROFILE_ID, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile));

        // 标记 profile 为运行中
        syncScheduler.markProfileRunning(PROFILE_ID);

        syncScheduler.triggerEventSync("TodoItem");

        // 等待一小段时间
        sleepQuietly(200);

        // 重叠时不应触发同步
        verify(syncEngine, never()).sync(any());

        // 清理
        syncScheduler.clearProfileRunning(PROFILE_ID);
    }

    // ---- 刷新调度测试 ----

    @Test
    void 刷新调度_更新Profile() {
        // 先启动调度
        var profile = buildProfile(PROFILE_ID, true, "0 */15 * * * *", "[\"TodoItem\"]");
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile));
        syncScheduler.start();
        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(1);

        // 刷新为新的 cron 表达式
        var updatedProfile = buildProfile(PROFILE_ID, true, "0 */30 * * * *", "[\"TodoItem\"]");
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(updatedProfile));

        syncScheduler.refreshSchedule(PROFILE_ID);

        // 仍然有 1 个调度任务
        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(1);
        assertThat(syncScheduler.getScheduledProfileIds()).containsExactly(PROFILE_ID);
    }

    @Test
    void 刷新调度_禁用Profile_取消调度() {
        // 先启动调度
        var profile = buildProfile(PROFILE_ID, true);
        when(profileRepository.findAllEnabled()).thenReturn(List.of(profile));
        syncScheduler.start();
        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(1);

        // 刷新为禁用状态
        var disabledProfile = buildProfile(PROFILE_ID, false);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(disabledProfile));

        syncScheduler.refreshSchedule(PROFILE_ID);

        // 调度任务应被取消
        assertThat(syncScheduler.getScheduledTaskCount()).isEqualTo(0);
    }

    // ---- Cron 解析测试 ----

    @Test
    void parseCron_每15分钟() {
        assertThat(syncScheduler.parseCronToIntervalMinutes("0 */15 * * * *")).isEqualTo(15);
    }

    @Test
    void parseCron_每30分钟() {
        assertThat(syncScheduler.parseCronToIntervalMinutes("0 */30 * * * *")).isEqualTo(30);
    }

    @Test
    void parseCron_每2小时() {
        assertThat(syncScheduler.parseCronToIntervalMinutes("0 0 */2 * * *")).isEqualTo(120);
    }

    @Test
    void parseCron_无法解析_使用默认值() {
        assertThat(syncScheduler.parseCronToIntervalMinutes("0 0 12 * * ?")).isEqualTo(15);
    }

    @Test
    void parseCron_null_使用默认值() {
        assertThat(syncScheduler.parseCronToIntervalMinutes(null)).isEqualTo(15);
    }

    // ---- dataType 匹配测试 ----

    @Test
    void profileMatchesDataType_匹配() {
        var profile = buildProfile(PROFILE_ID, true, "0 */15 * * * *", "[\"TodoItem\"]");
        assertThat(syncScheduler.profileMatchesDataType(profile, "TodoItem")).isTrue();
    }

    @Test
    void profileMatchesDataType_不匹配() {
        var profile = buildProfile(PROFILE_ID, true, "0 */15 * * * *", "[\"TodoItem\"]");
        assertThat(syncScheduler.profileMatchesDataType(profile, "ScheduleItem")).isFalse();
    }

    @Test
    void profileMatchesDataType_无过滤器_匹配所有() {
        var profile = buildProfile(PROFILE_ID, true, "0 */15 * * * *", null);
        assertThat(syncScheduler.profileMatchesDataType(profile, "TodoItem")).isTrue();
        assertThat(syncScheduler.profileMatchesDataType(profile, "ScheduleItem")).isTrue();
        assertThat(syncScheduler.profileMatchesDataType(profile, "HabitItem")).isTrue();
    }

    // ---- 辅助 ----

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
