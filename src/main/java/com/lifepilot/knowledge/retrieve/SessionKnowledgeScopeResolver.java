package com.lifepilot.knowledge.retrieve;

import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
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
    @Nullable
    private final SessionDatastoreRepository sessionDatastoreRepository;
    @Nullable
    private final KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;

    public SessionKnowledgeScopeResolver(
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable SessionDatastoreRepository sessionDatastoreRepository,
            @Nullable KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository) {
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.sessionDatastoreRepository = sessionDatastoreRepository;
        this.knowledgeBaseDatastoreRepository = knowledgeBaseDatastoreRepository;
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
        explicitKbIds.forEach(kbId -> scopes.add(new KnowledgeSearchScope(kbId, null)));

        if (sessionDatastoreRepository != null && knowledgeBaseDatastoreRepository != null) {
            for (String datastoreId : sessionDatastoreRepository.findDatastoreIdsBySessionId(sessionId)) {
                for (String kbId : knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(datastoreId)) {
                    if (explicitKbIds.contains(kbId)) {
                        continue;
                    }
                    scopes.add(new KnowledgeSearchScope(kbId, datastoreId));
                }
            }
        }

        return scopes.stream().distinct().toList();
    }
}
