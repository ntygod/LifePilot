package com.lifepilot.memory.governance;

import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.project.context.ProjectContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MemoryAccessPolicy 单元测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
class MemoryAccessPolicy_单元测试 {

    private final MemoryAccessPolicy policy = new MemoryAccessPolicy();

    @Test
    void 隔离项目读取继承但写入只允许项目space() {
        var ctx = new ProjectContext(
                "project-1",
                "space-project-1",
                "space-personal",
                "space-experience",
                true);

        var readFilter = policy.buildProjectReadFilter(ctx, Set.of(MemoryScope.USER_PROFILE));
        assertThat(readFilter.spaceIds())
                .containsExactlyInAnyOrder("space-project-1", "space-personal", "space-experience");
        assertThat(readFilter.scopes()).containsExactly(MemoryScope.USER_PROFILE);

        var writableFilter = policy.buildWritableEntityFilter(ctx);
        assertThat(writableFilter.spaceIds()).containsExactly("space-project-1");

        var writeContext = policy.buildProjectWriteContext(ctx, "session-1", "turn-1", null, "tool-update");
        assertThat(writeContext.spaceId()).isEqualTo("space-project-1");
        assertThat(writeContext.memoryScope()).isNull();
    }

    @Test
    void 主账户读取和写入都限制在默认个人与经验空间() {
        var ctx = ProjectContext.personal("space-personal", "space-experience");

        var readFilter = policy.buildProjectReadFilter(ctx, Set.of());
        assertThat(readFilter.spaceIds()).containsExactlyInAnyOrder("space-personal", "space-experience");

        var writableFilter = policy.buildWritableEntityFilter(ctx);
        assertThat(writableFilter.spaceIds()).containsExactlyInAnyOrder("space-personal", "space-experience");

        var readSpaces = policy.buildSnapshotReadSpaceIds(
                null,
                "space-personal",
                "space-experience",
                List.of("space-domain"));
        assertThat(readSpaces).containsExactly("space-personal", "space-experience", "space-domain");
    }

    @Test
    void 项目写入摘要读取应继承快照中的个人和经验空间() {
        var writeContext = new MemoryWriteContext(
                "space-project-1",
                null,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                "session-1",
                "session-1",
                "session-1",
                "turn-1",
                null,
                null,
                null);
        var snapshot = snapshot("space-personal", "space-experience");

        var filter = policy.buildSummaryReadFilter(writeContext, snapshot);

        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder("space-project-1", "space-personal", "space-experience");
        assertThat(filter.scopes()).isEmpty();
    }

    @Test
    void 项目写入摘要缺少治理快照或空间字段应失败() {
        var writeContext = new MemoryWriteContext(
                "space-project-1",
                null,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                "session-1",
                "session-1",
                "session-1",
                "turn-1",
                null,
                null,
                null);

        assertThatThrownBy(() -> policy.buildSummaryReadFilter(writeContext, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("项目写入摘要读取需要轮次治理快照");
        assertThatThrownBy(() -> policy.buildSummaryReadFilter(writeContext, snapshot(null, "space-experience")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照 personalSpaceId 不能为空");
        assertThatThrownBy(() -> policy.buildSnapshotReadSpaceIds(null, "space-personal", "", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experienceSpaceId 不能为空");
    }

    @Test
    void 快照读取空间传入空白项目或脏domain空间应失败() {
        assertThatThrownBy(() -> policy.buildSnapshotReadSpaceIds(" ", "space-personal", "space-experience", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectSpaceId 不能为空");

        assertThatThrownBy(() -> policy.buildSnapshotReadSpaceIds(
                null,
                "space-personal",
                "space-experience",
                List.of(" space-domain")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domainReadSpaceIds 不能包含首尾空白");
    }

    @Test
    void 自动学习个人写入应校验快照会话和轮次() {
        var snapshot = snapshot("space-personal", "space-experience");

        var writeContext = policy.resolveAutoLearningWriteContext(snapshot, "session-1", "turn-1");

        assertThat(writeContext).isNotNull();
        assertThat(writeContext.spaceId()).isEqualTo("space-project-1");
        assertThat(writeContext.memoryScope()).isNull();
        assertThat(writeContext.originType()).isEqualTo(MemoryOriginType.CHAT);
        assertThat(writeContext.sourceSessionId()).isEqualTo("session-1");
        assertThat(writeContext.sourceTurnId()).isEqualTo("turn-1");
    }

    @Test
    void 自动学习快照缺失或串线应失败() {
        assertThatThrownBy(() -> policy.resolveAutoLearningWriteContext(null, "session-1", "turn-1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("自动学习必须提供轮次治理快照");

        assertThatThrownBy(() -> policy.resolveAutoLearningWriteContext(snapshot("space-personal", "space-experience"),
                "other-session", "turn-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动学习快照会话不匹配");

        assertThatThrownBy(() -> policy.resolveAutoLearningWriteContext(snapshot("space-personal", "space-experience"),
                "session-1", "other-turn"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动学习快照轮次不匹配");
    }

    @Test
    void 自动学习明确禁用时返回null() {
        var snapshot = snapshot(
                "turn-1",
                "session-1",
                "space-personal",
                "space-experience",
                null,
                "space-project-1",
                false,
                false);

        var writeContext = policy.resolveAutoLearningWriteContext(snapshot, "session-1", "turn-1");

        assertThat(writeContext).isNull();
    }

    @Test
    void domain学习启用但缺写入空间应失败() {
        var snapshot = snapshot(
                "turn-1",
                "session-1",
                "space-personal",
                "space-experience",
                null,
                null,
                false,
                true);

        assertThatThrownBy(() -> policy.resolveAutoLearningWriteContext(snapshot, "session-1", "turn-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domainWriteSpaceId 不能为空");
    }

    @Test
    void domain学习写入应限定domainMemoryScope() {
        var snapshot = snapshot(
                "turn-1",
                "session-1",
                "space-personal",
                "space-experience",
                "space-domain-write",
                null,
                false,
                true);

        var writeContext = policy.resolveAutoLearningWriteContext(snapshot, "session-1", "turn-1");

        assertThat(writeContext).isNotNull();
        assertThat(writeContext.spaceId()).isEqualTo("space-domain-write");
        assertThat(writeContext.memoryScope()).isEqualTo(MemoryScope.DOMAIN_MEMORY);
        assertThat(writeContext.sourceSessionId()).isEqualTo("session-1");
        assertThat(writeContext.sourceTurnId()).isEqualTo("turn-1");
    }

    private ChatTurnMemorySnapshot snapshot(String personalSpaceId, String experienceSpaceId) {
        return snapshot(
                "turn-1",
                "session-1",
                personalSpaceId,
                experienceSpaceId,
                null,
                "space-project-1",
                true,
                false);
    }

    private ChatTurnMemorySnapshot snapshot(String turnId,
                                            String sessionId,
                                            String personalSpaceId,
                                            String experienceSpaceId,
                                            String domainWriteSpaceId,
                                            String projectSpaceId,
                                            boolean personalLearningEnabled,
                                            boolean domainLearningEnabled) {
        return new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                personalSpaceId,
                experienceSpaceId,
                domainWriteSpaceId,
                projectSpaceId,
                List.of("space-project-1", "space-personal", "space-experience"),
                List.of(),
                personalLearningEnabled,
                domainLearningEnabled,
                true,
                Map.of(),
                Instant.parse("2026-05-05T00:00:00Z"));
    }
}
