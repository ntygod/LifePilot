package com.lifepilot.knowledge.retrieve;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 会话知识检索范围解析器。
 *
 * @author zsg
 * @since 2026-03-26
 */
public class SessionKnowledgeScopeResolver {

    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;

    public SessionKnowledgeScopeResolver(
            SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository) {
        this.sessionKnowledgeBaseRepository = Objects.requireNonNull(sessionKnowledgeBaseRepository,
                "sessionKnowledgeBaseRepository");
    }

    public List<KnowledgeSearchScope> resolveScopes(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        var scopes = new ArrayList<KnowledgeSearchScope>();
        Set<String> explicitKbIds = new LinkedHashSet<>();

        explicitKbIds.addAll(sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId));
        explicitKbIds.forEach(kbId -> scopes.add(new KnowledgeSearchScope(kbId)));

        return scopes.stream().distinct().toList();
    }
}
