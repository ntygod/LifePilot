package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 心跳上下文包 — 框架级上下文，供三级检测和行为插件使用。
 *
 * @param userId               目标用户
 * @param now                  当前时间
 * @param zoneId               时区
 * @param quietHoursStart      安静时段开始（可为 null）
 * @param quietHoursEnd        安静时段结束（可为 null）
 * @param actionsSentToday     今日已发送的主动行为数
 * @param dailyMaxActions      每日最大主动行为数
 * @param focusState           桌面焦点状态（可为 null）
 * @param lastHeartbeatAt      上次心跳时间（首次运行为 null）
 * @param heartbeatIntervalMin 心跳间隔（分钟）
 * @author zsg
 * @since 2026-04-14
 */
public record ContextPacket(
        String userId,
        Instant now,
        ZoneId zoneId,
        @Nullable LocalTime quietHoursStart,
        @Nullable LocalTime quietHoursEnd,
        int actionsSentToday,
        int dailyMaxActions,
        @Nullable ReminderFocusState focusState,
        @Nullable Instant lastHeartbeatAt,
        int heartbeatIntervalMin,
        @Nullable String userProfile,
        @Nullable String recentExperience
) {

    private static final Pattern IDE_TITLE_PATTERN = Pattern.compile(
            "VS Code|Visual Studio Code|IntelliJ|WebStorm|PyCharm|CLion|GoLand|Rider|RustRover|Cursor|Zed|Neovim",
            Pattern.CASE_INSENSITIVE
    );

    public ContextPacket {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        Objects.requireNonNull(zoneId, "zoneId 不能为空");
        actionsSentToday = Math.max(0, actionsSentToday);
        dailyMaxActions = Math.max(1, dailyMaxActions);
        heartbeatIntervalMin = Math.max(1, heartbeatIntervalMin);
    }

    /**
     * Gate 1 判断：自上次心跳以来是否有变化。
     *
     * <p>Phase 1 策略（保守）：如果用户空闲超过心跳间隔，判定无变化。
     * 无焦点状态信息时默认有变化（无法判断用户是否在线）。</p>
     */
    public boolean hasChangeSinceLastHeartbeat() {
        if (lastHeartbeatAt == null) {
            return true;
        }
        if (focusState != null && focusState.idleMinutes() >= heartbeatIntervalMin) {
            return false;
        }
        return true;
    }

    /** 是否在安静时段内。 */
    public boolean isWithinQuietHours() {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false;
        }
        LocalTime localNow = LocalTime.ofInstant(now, zoneId);
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !localNow.isBefore(quietHoursStart) && localNow.isBefore(quietHoursEnd);
        }
        return !localNow.isBefore(quietHoursStart) || localNow.isBefore(quietHoursEnd);
    }

    /** 焦点应用是否全屏。 */
    public boolean isFullscreen() {
        return focusState != null && focusState.fullscreen();
    }

    /** 用户是否正在 IDE 编码。 */
    public boolean isFocusedCoding() {
        return focusState != null && focusState.focusTitle() != null
                && IDE_TITLE_PATTERN.matcher(focusState.focusTitle()).find();
    }

    /** 今日剩余投递额度。 */
    public int remainingSlots() {
        return Math.max(0, dailyMaxActions - actionsSentToday);
    }
}
