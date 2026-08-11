package com.lifepilot.memory.governance.lifecycle;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.events.SourceInvalidated;
import com.lifepilot.memory.governance.lifecycle.listeners.ProvenanceStaleListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link ProvenanceStaleListener} 单元测试 —— 验证 SourceInvalidated 到达时以当前时钟
 * 调用 {@link MemoryProvenanceRepository#markStale}。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class ProvenanceStaleListener_单元测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");

    @Mock
    MemoryProvenanceRepository repo;

    /** 用 Clock.fixed() 构造固定时钟，避免 @Mock Clock 带来的 stub 繁琐。 */
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private ProvenanceStaleListener listener() {
        return new ProvenanceStaleListener(repo, clock);
    }

    @Test
    void DOCUMENT删除应将对应provenance标STALE() {
        var event = new SourceInvalidated(SourceType.DOCUMENT, "doc-1", InvalidationKind.DELETED);

        listener().onSourceInvalidated(event);

        verify(repo).markStale(SourceType.DOCUMENT, "doc-1", FIXED_NOW);
    }

    @Test
    void KNOWLEDGE_BASE内容变更应标STALE() {
        var event = new SourceInvalidated(SourceType.KNOWLEDGE_BASE, "kb-42", InvalidationKind.CONTENT_CHANGED);

        listener().onSourceInvalidated(event);

        verify(repo).markStale(SourceType.KNOWLEDGE_BASE, "kb-42", FIXED_NOW);
    }

    @Test
    void SESSION失效也透传到markStale() {
        var event = new SourceInvalidated(SourceType.SESSION, "session-7", InvalidationKind.DELETED);

        listener().onSourceInvalidated(event);

        verify(repo).markStale(SourceType.SESSION, "session-7", FIXED_NOW);
    }

    @Test
    void markStale失败应直接抛出() {
        doThrow(new RuntimeException("DB 离线"))
                .when(repo).markStale(SourceType.DOCUMENT, "doc-x", FIXED_NOW);
        var event = new SourceInvalidated(SourceType.DOCUMENT, "doc-x", InvalidationKind.DELETED);

        assertThatThrownBy(() -> listener().onSourceInvalidated(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 离线");

        verify(repo).markStale(SourceType.DOCUMENT, "doc-x", FIXED_NOW);
    }

    @Test
    void 空事件应直接失败() {
        assertThatThrownBy(() -> listener().onSourceInvalidated(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("源失效事件不能为空");
    }

    @Test
    void 非法源失效事件应在构造时失败() {
        assertThatThrownBy(() -> new SourceInvalidated(null, "doc-1", InvalidationKind.DELETED))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("源对象类型不能为空");
        assertThatThrownBy(() -> new SourceInvalidated(SourceType.DOCUMENT, " ", InvalidationKind.DELETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源对象 ID 不能为空");
        assertThatThrownBy(() -> new SourceInvalidated(SourceType.DOCUMENT, " doc-1", InvalidationKind.DELETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("源对象 ID 不能包含首尾空白");
        assertThatThrownBy(() -> new SourceInvalidated(SourceType.DOCUMENT, "doc-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("源失效类型不能为空");
    }
}
