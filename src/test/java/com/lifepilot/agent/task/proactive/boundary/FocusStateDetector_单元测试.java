package com.lifepilot.agent.task.proactive.boundary;

import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FocusStateDetector 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class FocusStateDetector_单元测试 {

    private static final String USER = "u1";

    @Test
    void 全屏触发FOCUS_MODE() {
        var detector = new FocusStateDetector(5, 40);
        var focus = new ReminderFocusState("game.exe", "Full Screen Game", true, 0, Instant.now());
        assertThat(detector.detect(USER, focus)).isEqualTo(FocusMode.FOCUS_MODE);
    }

    @Test
    void IDE_Title触发FOCUS_MODE() {
        var detector = new FocusStateDetector(5, 40);
        var focus = new ReminderFocusState("code.exe", "index.ts - VS Code", false, 5, Instant.now());
        assertThat(detector.detect(USER, focus)).isEqualTo(FocusMode.FOCUS_MODE);
    }

    @Test
    void IDE_Title大小写不敏感() {
        var detector = new FocusStateDetector(5, 40);
        var focus = new ReminderFocusState("idea.exe", "project [intellij]", false, 5, Instant.now());
        assertThat(detector.detect(USER, focus)).isEqualTo(FocusMode.FOCUS_MODE);
    }

    @Test
    void 桌面活跃触发FOCUS_MODE() {
        var detector = new FocusStateDetector(5, 40);
        var focus = new ReminderFocusState("chrome.exe", "Google", false, 0, Instant.now());
        assertThat(detector.detect(USER, focus)).isEqualTo(FocusMode.FOCUS_MODE);
    }

    @Test
    void 陈旧桌面状态不触发活跃信号() {
        var detector = new FocusStateDetector(5, 40);
        var stale = new ReminderFocusState("chrome.exe", "Google", false, 0,
                Instant.now().minusSeconds(120));
        assertThat(detector.detect(USER, stale)).isEqualTo(FocusMode.NORMAL);
    }

    @Test
    void 高密度对话触发FOCUS_MODE() {
        var detector = new FocusStateDetector(5, 40);
        // 发送 6 条对话事件 — 快速间隔（默认 avg < 40s）
        var now = Instant.now();
        for (int i = 0; i < 6; i++) {
            detector.onConversationCompleted(
                    new ConversationCompletedEvent(this, USER, "s" + i, null));
        }

        assertThat(detector.detect(USER, null)).isEqualTo(FocusMode.FOCUS_MODE);
    }

    @Test
    void 低密度对话不触发() {
        var detector = new FocusStateDetector(5, 40);
        detector.onConversationCompleted(
                new ConversationCompletedEvent(this, USER, "s1", null));
        detector.onConversationCompleted(
                new ConversationCompletedEvent(this, USER, "s2", null));
        // 只有 2 条 < 阈值 5
        assertThat(detector.detect(USER, null)).isEqualTo(FocusMode.NORMAL);
    }

    @Test
    void 所有信号缺失返回NORMAL() {
        var detector = new FocusStateDetector(5, 40);
        assertThat(detector.detect(USER, null)).isEqualTo(FocusMode.NORMAL);
    }

    @Test
    void 空白IDE_Title不触发() {
        var detector = new FocusStateDetector(5, 40);
        var focus = new ReminderFocusState("app.exe", "", false, 5, Instant.now().minusSeconds(300));
        assertThat(detector.detect(USER, focus)).isEqualTo(FocusMode.NORMAL);
    }

    @Test
    void 用户无对话记录且空focusState() {
        var detector = new FocusStateDetector(5, 40);
        assertThat(detector.detect(null, null)).isEqualTo(FocusMode.NORMAL);
    }
}
