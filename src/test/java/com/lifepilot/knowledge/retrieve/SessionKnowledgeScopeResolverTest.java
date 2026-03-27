package com.lifepilot.knowledge.retrieve;

import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionKnowledgeScopeResolver 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class SessionKnowledgeScopeResolverTest {

    @Test
    void resolveScopes_显式知识库优先_并自动附加领域范围() {
        var sessionKnowledgeBaseRepository = mock(SessionKnowledgeBaseRepository.class);
        var sessionDatastoreRepository = mock(SessionDatastoreRepository.class);
        var knowledgeBaseDatastoreRepository = mock(KnowledgeBaseDatastoreRepository.class);
        when(sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId("session-1"))
                .thenReturn(List.of("kb-explicit", "kb-shared"));
        when(sessionDatastoreRepository.findDatastoreIdsBySessionId("session-1"))
                .thenReturn(List.of("ds-a", "ds-b"));
        when(knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId("ds-a"))
                .thenReturn(List.of("kb-shared", "kb-a"));
        when(knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId("ds-b"))
                .thenReturn(List.of("kb-b"));

        var resolver = new SessionKnowledgeScopeResolver(
                sessionKnowledgeBaseRepository,
                sessionDatastoreRepository,
                knowledgeBaseDatastoreRepository
        );

        List<KnowledgeSearchScope> scopes = resolver.resolveScopes("session-1");

        assertThat(scopes).containsExactly(
                new KnowledgeSearchScope("kb-explicit", null),
                new KnowledgeSearchScope("kb-shared", null),
                new KnowledgeSearchScope("kb-a", "ds-a"),
                new KnowledgeSearchScope("kb-b", "ds-b")
        );
    }

    @Test
    void resolveScopes_空会话id_返回空范围() {
        var resolver = new SessionKnowledgeScopeResolver(null, null, null);

        assertThat(resolver.resolveScopes("")).isEmpty();
        assertThat(resolver.resolveScopes("   ")).isEmpty();
        assertThat(resolver.resolveScopes(null)).isEmpty();
    }

    @Test
    void resolveScopes_缺少datastore仓储时_只返回显式知识库() {
        var sessionKnowledgeBaseRepository = mock(SessionKnowledgeBaseRepository.class);
        when(sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId("session-2"))
                .thenReturn(List.of("kb-only"));

        var resolver = new SessionKnowledgeScopeResolver(sessionKnowledgeBaseRepository, null, null);

        assertThat(resolver.resolveScopes("session-2"))
                .containsExactly(new KnowledgeSearchScope("kb-only", null));
    }
}
