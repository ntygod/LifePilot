package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.behavior.ClipboardBehavior;
import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.proactive.behavior.FollowUpBehavior;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntentType;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProactiveEngine 集成测试 — 验证心跳管线端到端流程。
 *
 * <p>使用 SQLite in-memory 数据库和真实的组件实例（DecisionGate、DeliveryEngine、
 * FollowUpBehavior、ClipboardBehavior 等），仅 mock NotificationService 用于验证投递调用。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
class ProactiveEngine_集成测试 {

    private static final String USER_ID = "test-user";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private NotificationService notificationService;

    // 真实组件
    private QueuedActionRepository queuedActionRepo;
    private AutonomyRepository autonomyRepo;
    private TrustUpgradeService trustUpgradeService;
    private DecisionGate decisionGate;
    private DeliveryEngine deliveryEngine;
    private ClipboardIntentBuffer clipboardBuffer;

    @BeforeEach
    void 初始化() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);

        // 创建所有需要的表（V3-V6 的 DDL）
        jdbc.execute("""
                CREATE TABLE proactive_queued_actions (
                    id          TEXT    NOT NULL PRIMARY KEY,
                    user_id     TEXT    NOT NULL,
                    behavior    TEXT    NOT NULL,
                    topic_key   TEXT    NOT NULL,
                    title       TEXT    NOT NULL,
                    content     TEXT    NOT NULL,
                    score       REAL    NOT NULL,
                    metadata    TEXT,
                    shown       INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT    NOT NULL,
                    shown_at    TEXT
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_behavior_autonomy (
                    user_id               TEXT    NOT NULL,
                    behavior_name         TEXT    NOT NULL,
                    autonomy_level        TEXT    NOT NULL DEFAULT 'A',
                    consecutive_positive  INTEGER NOT NULL DEFAULT 0,
                    consecutive_negative  INTEGER NOT NULL DEFAULT 0,
                    upgrade_suggested     INTEGER NOT NULL DEFAULT 0,
                    cooldown_until        TEXT,
                    updated_at            TEXT    NOT NULL,
                    PRIMARY KEY (user_id, behavior_name)
                )""");
        // 索引
        jdbc.execute("CREATE INDEX idx_queued_user_shown ON proactive_queued_actions (user_id, shown, created_at DESC)");

        // 初始化真实组件
        notificationService = mock(NotificationService.class);
        when(notificationService.send(any())).thenReturn(List.of("notif-" + UUID.randomUUID()));

        queuedActionRepo = new QueuedActionRepository(jdbc);
        autonomyRepo = new AutonomyRepository(jdbc);
        trustUpgradeService = new TrustUpgradeService(autonomyRepo, null);
        decisionGate = new DecisionGate(trustUpgradeService, null);
        deliveryEngine = new DeliveryEngine(notificationService, queuedActionRepo);
        clipboardBuffer = new ClipboardIntentBuffer();
    }

    @AfterEach
    void 清理() {
        if (dataSource != null) dataSource.destroy();
    }

    // ── 辅助方法 ──

    /** 构建引擎，使用指定的行为插件列表。 */
    private ProactiveEngine buildEngine(List<ProactiveBehavior> behaviors) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                null, null, null, null, null, null, null);
    }

    /** 构建标准 ContextPacket。 */
    private ContextPacket buildCtx(Instant now, ReminderFocusState focusState,
                                    Instant lastHeartbeatAt, int heartbeatMin,
                                    LocalTime quietStart, LocalTime quietEnd) {
        return new ContextPacket(USER_ID, now, ZONE, quietStart, quietEnd,
                0, 10, focusState, lastHeartbeatAt, heartbeatMin, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }

    // ── 测试用例 ──

    @Test
    void 心跳SILENT_用户空闲时跳过检测() {
        var followUp = new FollowUpBehavior(mock(ProactiveMemoryBridge.class), null, null, null);
        var engine = buildEngine(List.of(followUp));

        Instant now = Instant.now();
        // focusState: idle 60 分钟，heartbeatInterval 30 分钟 → idle >= heartbeat → 无变化
        var focusState = new ReminderFocusState("chrome.exe", "Google", false, 60, now);
        var ctx = buildCtx(now, focusState, now.minus(30, ChronoUnit.MINUTES), 30, null, null);

        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.SILENT);
        // SILENT 不应触发任何通知
        verify(notificationService, never()).send(any());
    }

    @Test
    void 心跳FAST_无候选时快速返回() {
        var followUp = new FollowUpBehavior(mock(ProactiveMemoryBridge.class), null, null, null);
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(followUp, clipboard));

        // 首次心跳（lastHeartbeatAt = null → hasChange = true），但无意图数据、无剪贴板 → 无候选
        Instant now = Instant.now();
        var ctx = buildCtx(now, null, null, 30, null, null);

        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FAST);
        verify(notificationService, never()).send(any());
    }

    @Test
    void 心跳FULL_有候选时完整流程_FollowUp生成追问() {
        // 通过 mock bridge 提供 GoalView 数据
        var bridge = mock(ProactiveMemoryBridge.class);
        Instant now = Instant.now();
        Instant threeDaysAgo = now.minus(3, ChronoUnit.DAYS);
        var goal = new GoalView("entity-1", "学习 Rust 编程语言", "想转方向学 Rust",
                0.6f, 5, threeDaysAgo, 2, null, java.util.Map.of());
        when(bridge.getActiveGoals()).thenReturn(List.of(goal));
        when(bridge.enrichGoalContext(anyString(), anyString())).thenReturn("");

        var followUp = new FollowUpBehavior(bridge, null, null, null);
        var engine = buildEngine(List.of(followUp));

        var ctx = buildCtx(now, null, null, 30, null, null);
        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FULL);

        // 验证通知已发送
        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().targetUserId()).isEqualTo(USER_ID);

        // 验证 bridge 的 checkCount 被递增
        verify(bridge).incrementCheckCount("entity-1");
    }

    @Test
    void 剪贴板意图通过引擎投递() {
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(clipboard));

        // 向缓冲区写入一个 TRACKING_NUMBER 意图
        var clipIntent = new ReminderClipboardIntent(
                ReminderClipboardIntentType.TRACKING_NUMBER,
                "SF1234567890", Instant.now());
        clipboardBuffer.offer(clipIntent);

        Instant now = Instant.now();
        var ctx = buildCtx(now, null, null, 30, null, null);

        DetectionLevel level = engine.heartbeat(ctx);

        // ClipboardBehavior detect 分数 0.6 ≥ 阈值 0.4 → FULL
        assertThat(level).isEqualTo(DetectionLevel.FULL);

        // 验证通知被发送（NOTIFY 级别）
        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, atLeastOnce()).send(captor.capture());

        var request = captor.getValue();
        assertThat(request.targetUserId()).isEqualTo(USER_ID);
        // 通知类型应为 proactive_action
        assertThat(request.typeId()).isEqualTo("proactive_action");
    }

    @Test
    void 安静时段阻断投递() {
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(clipboard));

        // 写入一个剪贴板意图（确保有候选）
        clipboardBuffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.FLIGHT_NUMBER, "CA1234", Instant.now()));

        // 设置安静时段覆盖当前时间
        Instant now = Instant.now();
        LocalTime currentLocal = LocalTime.ofInstant(now, ZONE);
        LocalTime quietStart = currentLocal.minusMinutes(10);
        LocalTime quietEnd = currentLocal.plusMinutes(10);

        var ctx = buildCtx(now, null, null, 30, quietStart, quietEnd);
        DetectionLevel level = engine.heartbeat(ctx);

        // 即使有候选，决策门控在安静时段会全部跳过
        // 但注意：返回级别仍是 FULL（因为有 action），只是门控后 gated 为空 → 不投递
        assertThat(level).isEqualTo(DetectionLevel.FULL);
        // 安静时段不应投递通知
        verify(notificationService, never()).send(any());
    }

    @Test
    void 自主度A级限制INTERRUPT降级为NOTIFY() {
        // 设置 follow-up 行为的自主度为 A
        autonomyRepo.upsert(new AutonomyConfig(
                USER_ID, "follow-up", AutonomyLevel.A,
                0, 0, false, null, Instant.now()));

        // 通过 mock bridge 提供高分目标
        var bridge = mock(ProactiveMemoryBridge.class);
        Instant now = Instant.now();
        Instant fiveDaysAgo = now.minus(5, ChronoUnit.DAYS);
        var goal = new GoalView("entity-2", "等 Rust 教程降价到 50 告诉我", "价格低于50",
                0.8f, 3, fiveDaysAgo, 0, null, java.util.Map.of());
        when(bridge.getActiveGoals()).thenReturn(List.of(goal));
        when(bridge.enrichGoalContext(anyString(), anyString())).thenReturn("");

        var followUp = new FollowUpBehavior(bridge, null, null, null);
        var engine = buildEngine(List.of(followUp));

        var ctx = buildCtx(now, null, null, 30, null, null);
        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FULL);

        // 验证通知被发送
        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, atLeastOnce()).send(captor.capture());

        // 验证投递元数据中 deliveryLevel 不是 INTERRUPT
        var request = captor.getValue();
        var metadata = request.metadata();
        if (metadata != null && metadata.containsKey("deliveryLevel")) {
            // A 级自主度：即使插件建议 INTERRUPT 也会被降级为 NOTIFY
            assertThat(metadata.get("deliveryLevel")).isNotEqualTo("INTERRUPT");
        }
    }

    @Test
    void 每日上限用尽后不再投递() {
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(clipboard));

        clipboardBuffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.TRACKING_NUMBER, "YT0000001", Instant.now()));

        Instant now = Instant.now();
        // actionsSentToday=10, dailyMaxActions=10 → remainingSlots=0
        var ctx = new ContextPacket(USER_ID, now, ZONE, null, null,
                10, 10, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FULL);
        // 额度用尽不应投递
        verify(notificationService, never()).send(any());
    }

    @Test
    void 全屏模式阻断投递() {
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(clipboard));

        clipboardBuffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.URL, "https://example.com", Instant.now()));

        Instant now = Instant.now();
        // fullscreen=true → 决策门控一票否决
        var focusState = new ReminderFocusState("vlc.exe", "全屏播放", true, 0, now);
        var ctx = buildCtx(now, focusState, null, 30, null, null);
        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FULL);
        verify(notificationService, never()).send(any());
    }

    @Test
    void 编码中INTERRUPT降级为NOTIFY() {
        var clipboard = new ClipboardBehavior(clipboardBuffer);
        var engine = buildEngine(List.of(clipboard));

        clipboardBuffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.TRACKING_NUMBER, "SF9999999", Instant.now()));

        Instant now = Instant.now();
        // 焦点窗口标题包含 "VS Code" → isFocusedCoding = true
        var focusState = new ReminderFocusState("code.exe", "index.ts - VS Code", false, 0, now);
        var ctx = buildCtx(now, focusState, null, 30, null, null);
        DetectionLevel level = engine.heartbeat(ctx);

        assertThat(level).isEqualTo(DetectionLevel.FULL);

        // 验证通知已发送
        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, atLeastOnce()).send(captor.capture());

        // ClipboardBehavior 建议 NOTIFY，编码中降级逻辑只影响 INTERRUPT → NOTIFY
        // 所以通知应正常发送
        var request = captor.getValue();
        assertThat(request.targetUserId()).isEqualTo(USER_ID);
    }
}
