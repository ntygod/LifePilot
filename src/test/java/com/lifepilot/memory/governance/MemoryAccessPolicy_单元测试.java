package com.lifepilot.memory.governance;

import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.project.context.ProjectContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
