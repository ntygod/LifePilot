package com.lifepilot.agent.task.proactive.cot;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.agent.task.proactive.training.ProactiveFewShotLibrary;
import com.lifepilot.agent.task.proactive.training.ProactiveFewShotSample;
import com.lifepilot.agent.task.reminder.ReminderAction;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GateThreeReasoner 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class GateThreeReasoner_单元测试 {

    @Test
    void shouldApply_开关关闭时始终返回false(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveCotEnabled(false);
        var reasoner = new GateThreeReasoner(lib, cfg, null);

        var candidate = candidate(0.9f);
        assertThat(reasoner.shouldApply(candidate)).isFalse();
    }

    @Test
    void shouldApply_开关开启且分数足够时返回true(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveCotEnabled(true);
        cfg.setProactiveCotMinScore(0.6f);
        var reasoner = new GateThreeReasoner(lib, cfg, null);

        assertThat(reasoner.shouldApply(candidate(0.7f))).isTrue();
        assertThat(reasoner.shouldApply(candidate(0.5f))).isFalse();
    }

    @Test
    void buildStructuredPrompt_包含四段结构(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveCotEnabled(true);
        var reasoner = new GateThreeReasoner(lib, cfg, null);

        String prompt = reasoner.buildStructuredPrompt(candidate(0.8f), ctx(), "测试文案");

        assertThat(prompt).contains("<observation>");
        assertThat(prompt).contains("<user-state>");
        assertThat(prompt).contains("<necessity>");
        assertThat(prompt).contains("<action>");
        assertThat(prompt).contains("测试文案");
    }

    @Test
    void buildStructuredPrompt_有few_shot样例时包含在prompt中(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var samples = List.of(
                new ProactiveFewShotSample("reminder", "吃药", ReminderAction.NORMAL_PUSH,
                        0.9f, "score=0.8,outcome=ACTED", Instant.now(), true),
                new ProactiveFewShotSample("reminder", "运动", ReminderAction.SOFT_PUSH,
                        0.15f, "score=0.3,outcome=DISMISSED", Instant.now(), false)
        );
        var np = new NotificationProperties();
        np.setDefaultUserId("test");
        lib.save("test", samples);

        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveCotEnabled(true);
        var reasoner = new GateThreeReasoner(lib, cfg, np);

        String prompt = reasoner.buildStructuredPrompt(candidate(0.8f), ctx(), "基础文案");

        assertThat(prompt).contains("reward=0.90");
        assertThat(prompt).contains("outcome=positive");
        assertThat(prompt).contains("outcome=ACTED");
    }

    @Test
    void parseAction_成功解析有效action(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        var reasoner = new GateThreeReasoner(lib, cfg, null);

        String response = "<action>NORMAL_PUSH</action>";
        assertThat(reasoner.parseAction(response)).contains(ReminderAction.NORMAL_PUSH);

        assertThat(reasoner.parseAction("  <action>\n  SKIP  \n  </action>"))
                .contains(ReminderAction.SKIP);
    }

    @Test
    void parseAction_无法解析时返回空(@TempDir Path tempDir) {
        var lib = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        var reasoner = new GateThreeReasoner(lib, cfg, null);

        assertThat(reasoner.parseAction("没有 action 标签")).isEmpty();
        assertThat(reasoner.parseAction("<action>UNKNOWN_VALUE</action>")).isEmpty();
        assertThat(reasoner.parseAction(null)).isEmpty();
        assertThat(reasoner.parseAction("")).isEmpty();
    }

    private ProactiveCandidate candidate(float score) {
        return new ProactiveCandidate(
                "c1", "reminder", "topic-1", "测试标题", score, "测试理由", null);
    }

    private ContextPacket ctx() {
        return new ContextPacket("test", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.IN_BOUNDARY, FocusMode.NORMAL);
    }
}
