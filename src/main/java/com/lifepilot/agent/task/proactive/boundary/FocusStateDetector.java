package com.lifepilot.agent.task.proactive.boundary;

import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * 用户专注状态检测器 — 综合桌面焦点状态 + 对话消息节奏，推断 {@link FocusMode}。
 *
 * <p>与既有 {@code ImplicitSignalCollector} 的差异：
 * <ul>
 *   <li>ImplicitSignalCollector 是"事后观察"：推送后看用户是否忽略/参与</li>
 *   <li>FocusStateDetector 是"事前识别"：推送前判断用户是否处于专注状态，
 *       让 {@code DecisionGate} 能在投递级别层面降级</li>
 * </ul>
 * </p>
 *
 * <p>信号优先级（短路判断）：
 * <ol>
 *   <li>桌面全屏 → FOCUS_MODE</li>
 *   <li>桌面 IDE 焦点（ContextPacket 的 IDE_TITLE_PATTERN 识别） → FOCUS_MODE</li>
 *   <li>桌面"当前活跃"信号（idleMinutes&lt;1 且上报时间 ≤ 60 秒）→ FOCUS_MODE</li>
 *   <li>对话高密度（5 分钟内消息数 ≥ 阈值 且 平均间隔 ≤ 阈值）→ FOCUS_MODE</li>
 *   <li>否则 NORMAL</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class FocusStateDetector {

    private static final Logger log = LoggerFactory.getLogger(FocusStateDetector.class);

    /** IDE 焦点识别正则 — 与 ContextPacket 保持一致，避免模式漂移。 */
    private static final Pattern IDE_TITLE_PATTERN = Pattern.compile(
            "VS Code|Visual Studio Code|IntelliJ|WebStorm|PyCharm|CLion|GoLand|Rider|RustRover|Cursor|Zed|Neovim",
            Pattern.CASE_INSENSITIVE
    );

    /** 对话消息队列容量上限（每用户）。 */
    private static final int MAX_MESSAGES_PER_USER = 20;

    /** 对话密度统计窗口 — 5 分钟。 */
    private static final Duration DIALOG_WINDOW = Duration.ofMinutes(5);

    /** 桌面活跃判定：idle 上报时间距 now 的最大容忍。 */
    private static final Duration DESKTOP_ACTIVE_STALENESS = Duration.ofSeconds(60);

    private final int messageDensityThreshold;
    private final int messageIntervalSeconds;

    /** userId → 最近对话消息时间（头部最新）。 */
    private final Map<String, Deque<Instant>> recentMessages = new ConcurrentHashMap<>();

    public FocusStateDetector(int messageDensityThreshold, int messageIntervalSeconds) {
        this.messageDensityThreshold = Math.max(1, messageDensityThreshold);
        this.messageIntervalSeconds = Math.max(1, messageIntervalSeconds);
    }

    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        recordMessage(event.getUserId(), Instant.now());
    }

    /**
     * 判定用户当前是否处于专注状态。
     *
     * @param userId     用户 ID
     * @param focusState 可选的桌面焦点状态（由 Tauri 上报，缺失时仅用对话信号）
     * @return FocusMode
     */
    public FocusMode detect(@Nullable String userId, @Nullable ReminderFocusState focusState) {
        // 1. 桌面全屏
        if (focusState != null && focusState.fullscreen()) {
            return FocusMode.FOCUS_MODE;
        }
        // 2. 桌面 IDE 焦点
        if (focusState != null && focusState.focusTitle() != null
                && !focusState.focusTitle().isBlank()
                && IDE_TITLE_PATTERN.matcher(focusState.focusTitle()).find()) {
            return FocusMode.FOCUS_MODE;
        }
        // 3. 桌面"当前活跃"
        if (focusState != null
                && focusState.idleMinutes() < 1
                && focusState.timestamp() != null
                && Duration.between(focusState.timestamp(), Instant.now())
                        .compareTo(DESKTOP_ACTIVE_STALENESS) <= 0) {
            return FocusMode.FOCUS_MODE;
        }
        // 4. 对话高密度
        if (isDialogDense(userId)) {
            return FocusMode.FOCUS_MODE;
        }
        return FocusMode.NORMAL;
    }

    private boolean isDialogDense(@Nullable String userId) {
        if (userId == null || userId.isBlank()) return false;
        Deque<Instant> messages = recentMessages.get(userId);
        if (messages == null || messages.size() < messageDensityThreshold) return false;

        Instant now = Instant.now();
        Instant cutoff = now.minus(DIALOG_WINDOW);
        List<Instant> within = messages.stream()
                .filter(t -> t.isAfter(cutoff))
                .sorted()
                .toList();
        if (within.size() < messageDensityThreshold) return false;
        if (within.size() < 2) return false;

        long totalSec = 0;
        for (int i = 1; i < within.size(); i++) {
            totalSec += Duration.between(within.get(i - 1), within.get(i)).toSeconds();
        }
        double avgSec = (double) totalSec / (within.size() - 1);
        return avgSec <= messageIntervalSeconds;
    }

    private void recordMessage(@Nullable String userId, Instant at) {
        if (userId == null || userId.isBlank()) return;
        Deque<Instant> deque = recentMessages.computeIfAbsent(userId, _ -> new ConcurrentLinkedDeque<>());
        deque.addFirst(at);
        while (deque.size() > MAX_MESSAGES_PER_USER) {
            deque.pollLast();
        }
        log.debug("FocusDetector 消息记录: userId={}, queueSize={}", userId, deque.size());
    }
}
