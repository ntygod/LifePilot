package com.lifepilot.knowledge.retrieve;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话知识检索范围解析器。
 *
 * @author zsg
 * @since 2026-03-26
 */
public class SessionKnowledgeScopeResolver {

    @Nullable
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;

    public SessionKnowledgeScopeResolver(
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository) {
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
    }

    public List<KnowledgeSearchScope> resolveScopes(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        var scopes = new ArrayList<KnowledgeSearchScope>();
        Set<String> explicitKbIds = new LinkedHashSet<>();

        if (sessionKnowledgeBaseRepository != null) {
            explicitKbIds.addAll(sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId));
        }
        explicitKbIds.forEach(kbId -> scopes.add(new KnowledgeSearchScope(kbId)));

        return scopes.stream().distinct().toList();
    }
}
