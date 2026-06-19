package com.lifepilot.memory.experience;

import com.lifepilot.agent.learning.experience.SubtaskReflector;
import com.lifepilot.agent.learning.experience.ToolTipResolver;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolTipResolver 单元测试。
 *
 * @author zsg
 * @since 2026-04-17
 */
@ExtendWith(MockitoExtension.class)
class ToolTipResolverTest {

    @Test
    void semanticMemory为null时构造失败() {
        assertThatThrownBy(() -> new ToolTipResolver(
                null,
                mock(ProjectContextResolver.class),
                mock(ChatSessionRepository.class),
                new MemoryAccessPolicy()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolId为null或空白时返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        var resolver = resolver(semanticMemory);

        assertThat(resolver.tipsFor(null, null)).isEmpty();
        assertThat(resolver.tipsFor("", null)).isEmpty();
        assertThat(resolver.tipsFor("  ", null)).isEmpty();
        verify(semanticMemory, never()).findCurrentByType(any(), any());
    }

    @Test
    void 无匹配经验时返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of());

        var resolver = resolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read", null)).isEmpty();
    }

    @Test
    void 匹配TOOL_LEVEL经验时返回历史经验提示前缀() {
        var semanticMemory = mock(SemanticMemory.class);
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("注意 skill 参数不要带空格"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = resolver(semanticMemory);
        String tips = resolver.tipsFor("file.read", null);

        assertThat(tips)
                .startsWith("[历史经验提示]")
                .contains("注意 skill 参数不要带空格");
    }

    @Test
    void 不同toolId的经验不会交叉命中() {
        var semanticMemory = mock(SemanticMemory.class);
        var other = experience("web.search",
                SubtaskReflector.TOOL_LEVEL,
                List.of("搜索结果可能过期"),
                0.8f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(other));

        var resolver = resolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read", null)).isEmpty();
    }

    @Test
    void 非TOOL_LEVEL的经验被过滤掉() {
        var semanticMemory = mock(SemanticMemory.class);
        var taskLevel = experience("file.read",
                "TASK_LEVEL",
                List.of("任务级经验"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(taskLevel));

        var resolver = resolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read", null)).isEmpty();
    }

    @Test
    void 缓存在TTL内只查询一次() {
        var semanticMemory = mock(SemanticMemory.class);
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("教训 A"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = resolver(semanticMemory);
        resolver.tipsFor("file.read", null);
        resolver.tipsFor("file.read", null);
        resolver.tipsFor("file.read", null);

        // 同一 toolId 三次查询只应触发一次 SemanticMemory 读取
        verify(semanticMemory, org.mockito.Mockito.times(1))
                .findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class));
    }

    @Test
    void 多条匹配经验按importanceScore降序取top2() {
        var semanticMemory = mock(SemanticMemory.class);
        var low = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("低优"), 0.1f);
        var high = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("最高优"), 0.95f);
        var mid = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("次优"), 0.7f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(low, high, mid));

        var resolver = resolver(semanticMemory);
        String tips = resolver.tipsFor("file.read", null);

        assertThat(tips)
                .contains("最高优")
                .contains("次优")
                .doesNotContain("低优");
    }

    @Test
    void 查询抛异常时降级返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenThrow(new RuntimeException("DB down"));

        var resolver = resolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read", null)).isEmpty();
    }

    @Test
    void 带sessionId时按项目上下文构造经验读取filter() {
        var semanticMemory = mock(SemanticMemory.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var session = ChatSession.createWithId("session-1", "项目对话");
        var context = new ProjectContext(
                "project-1",
                "space-project",
                "space-personal",
                "space-experience",
                true);
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("项目内经验"),
                0.9f);
        when(chatSessionRepository.findById("session-1")).thenReturn(Optional.of(
                new ChatSession(session.id(), session.title(), session.summary(), session.messageCount(),
                        session.isPinned(), session.archived(), session.lastMessageAt(),
                        session.createdAt(), session.updatedAt(), "project-1")));
        when(projectContextResolver.resolve("project-1")).thenReturn(context);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = new ToolTipResolver(
                semanticMemory,
                projectContextResolver,
                chatSessionRepository,
                new MemoryAccessPolicy());

        String tips = resolver.tipsFor("file.read", "session-1");

        assertThat(tips).contains("项目内经验");
        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(semanticMemory).findCurrentByType(eq(EntityType.EXPERIENCE), captor.capture());
        assertThat(captor.getValue().spaceIds())
                .containsExactlyInAnyOrder("space-project", "space-personal", "space-experience");
    }

    @Test
    void 主账户session通过解析器构造主账户经验读取filter() {
        var semanticMemory = mock(SemanticMemory.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var session = ChatSession.createWithId("session-personal", "主账户对话");
        var context = ProjectContext.personal("space-personal", "space-experience");
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("主账户经验"),
                0.9f);
        when(chatSessionRepository.findById("session-personal")).thenReturn(Optional.of(session));
        when(projectContextResolver.resolve(null)).thenReturn(context);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = new ToolTipResolver(
                semanticMemory,
                projectContextResolver,
                chatSessionRepository,
                new MemoryAccessPolicy());

        String tips = resolver.tipsFor("file.read", "session-personal");

        assertThat(tips).contains("主账户经验");
        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(semanticMemory).findCurrentByType(eq(EntityType.EXPERIENCE), captor.capture());
        assertThat(captor.getValue().spaceIds())
                .containsExactlyInAnyOrder("space-personal", "space-experience");
        verify(projectContextResolver).resolve(null);
    }

    @Test
    void 项目上下文解析失败时返回空串且不读取记忆() {
        var semanticMemory = mock(SemanticMemory.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var session = ChatSession.createWithId("session-2", "项目对话");
        when(chatSessionRepository.findById("session-2")).thenReturn(Optional.of(
                new ChatSession(session.id(), session.title(), session.summary(), session.messageCount(),
                        session.isPinned(), session.archived(), session.lastMessageAt(),
                        session.createdAt(), session.updatedAt(), "project-missing")));
        when(projectContextResolver.resolve("project-missing"))
                .thenThrow(new IllegalStateException("项目不存在"));

        var resolver = new ToolTipResolver(
                semanticMemory,
                projectContextResolver,
                chatSessionRepository,
                new MemoryAccessPolicy());

        assertThat(resolver.tipsFor("file.read", "session-2")).isEmpty();
        verify(semanticMemory, never()).findCurrentByType(any(), any());
    }

    // ==================== helper ====================

    private static ToolTipResolver resolver(SemanticMemory semanticMemory) {
        var projectContextResolver = mock(ProjectContextResolver.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        lenient().when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
        return new ToolTipResolver(
                semanticMemory,
                projectContextResolver,
                chatSessionRepository,
                new MemoryAccessPolicy());
    }

    private static TemporalEntity experience(String toolId,
                                             String granularity,
                                             List<String> lessons,
                                             float importance) {
        Instant now = Instant.now();
        return new TemporalEntity(
                "exp-" + toolId + "-" + granularity,
                EntityType.EXPERIENCE,
                "experience for " + toolId,
                null,
                Map.of("toolId", toolId, "granularity", granularity, "lessons", lessons),
                1,
                true,
                now,
                null,
                null,
                1.0f,
                importance,
                0,
                null,
                now,
                now)
                .withQuality(MemoryEvidenceKind.LLM_SUMMARIZED_EXPERIENCE,
                        MemoryTrustLevel.DERIVED, 0.7f, 1, null);
    }
}
