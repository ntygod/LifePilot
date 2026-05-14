package com.lifepilot.agent.task.proactive.boundary;

import com.lifepilot.agent.suspend.event.A2aTaskCompletedEvent;
import com.lifepilot.agent.suspend.event.WorkflowCompletedEvent;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BoundarySignalCollector 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class BoundarySignalCollector_单元测试 {

    private static final String USER = "u1";

    @Test
    void 对话完成事件触发边界窗口内() {
        var collector = new BoundarySignalCollector(10, null);
        collector.onConversationCompleted(new ConversationCompletedEvent(this, USER, "s1", null));

        assertThat(collector.isWithinBoundary(USER, Instant.now())).isTrue();
    }

    @Test
    void 工作流完成事件使用defaultUserId() {
        var np = new NotificationProperties();
        np.setDefaultUserId("default-user");
        var collector = new BoundarySignalCollector(10, np);

        collector.onWorkflowCompleted(new WorkflowCompletedEvent("exec-1", "COMPLETED", "{}"));

        assertThat(collector.isWithinBoundary("default-user", Instant.now())).isTrue();
        assertThat(collector.isWithinBoundary("other-user", Instant.now())).isFalse();
    }

    @Test
    void A2A完成事件使用defaultUserId() {
        var np = new NotificationProperties();
        np.setDefaultUserId("default-user");
        var collector = new BoundarySignalCollector(10, np);

        collector.onA2aTaskCompleted(new A2aTaskCompletedEvent("task-1", "{}"));

        assertThat(collector.isWithinBoundary("default-user", Instant.now())).isTrue();
    }

    @Test
    void 工作流事件无defaultUserId时静默跳过() {
        var collector = new BoundarySignalCollector(10, null);
        collector.onWorkflowCompleted(new WorkflowCompletedEvent("exec-1", "COMPLETED", "{}"));

        // 无 userId 时不记录任何事件
        assertThat(collector.snapshot("any-user")).isEmpty();
    }

    @Test
    void 窗口外返回false() {
        var collector = new BoundarySignalCollector(10, null);
        var now = Instant.parse("2026-05-09T10:00:00Z");

        // 模拟一个 20 分钟前的事件（超出 10 分钟窗口）
        // 直接测试 isWithinBoundary 逻辑：用 snapshot 反证
        collector.onConversationCompleted(new ConversationCompletedEvent(this, USER, "s1", null));
        // 现在立即判定应为 true
        assertThat(collector.isWithinBoundary(USER, Instant.now())).isTrue();

        // 查询 30 分钟后：超出窗口
        assertThat(collector.isWithinBoundary(USER, Instant.now().plusSeconds(1900))).isFalse();
    }

    @Test
    void 未知用户返回false() {
        var collector = new BoundarySignalCollector(10, null);
        assertThat(collector.isWithinBoundary("ghost", Instant.now())).isFalse();
        assertThat(collector.isWithinBoundary(null, Instant.now())).isFalse();
        assertThat(collector.isWithinBoundary("", Instant.now())).isFalse();
    }

    @Test
    void 容量硬上限32条() {
        var collector = new BoundarySignalCollector(10, null);
        for (int i = 0; i < 40; i++) {
            collector.onConversationCompleted(new ConversationCompletedEvent(this, USER, "s" + i, null));
        }
        assertThat(collector.snapshot(USER).size()).isLessThanOrEqualTo(32);
    }

    @Test
    void 多用户独立隔离() {
        var collector = new BoundarySignalCollector(10, null);
        collector.onConversationCompleted(new ConversationCompletedEvent(this, "u1", "s1", null));
        collector.onConversationCompleted(new ConversationCompletedEvent(this, "u2", "s2", null));

        assertThat(collector.isWithinBoundary("u1", Instant.now())).isTrue();
        assertThat(collector.isWithinBoundary("u2", Instant.now())).isTrue();
        assertThat(collector.isWithinBoundary("u3", Instant.now())).isFalse();
    }
}
