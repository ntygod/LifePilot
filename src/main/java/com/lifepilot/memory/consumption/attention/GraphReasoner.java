package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.store.scope.MemoryReadFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 图联想推理器（memory-proactive-foundation）—— 基于已打通的 {@code memory_relations} 关系图，
 * 用 Java BFS 做带关系类型的多跳路径检索与连接机会发现。
 *
 * <p>只读、无副作用；只沿 {@code memory_relations.status='ACTIVE'} 且当前 relation version
 * {@code valid_to IS NULL} 的边遍历，端点必须为可召回生命周期的当前实体（与 {@code GraphTraverser} 口径一致）。
 * 逐层扩展、去环、按 {@code maxFanout} 限流，保证有界终止。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
public class GraphReasoner {

    private static final Logger log = LoggerFactory.getLogger(GraphReasoner.class);
    private final JdbcTemplate jdbcTemplate;
    private final int maxFanout;
    /** 关系最低可信分门控；trust_score < 此值的边被跳过。 */
    private final float minRelationTrust;

    public GraphReasoner(JdbcTemplate jdbcTemplate, int maxFanout) {
        this(jdbcTemplate, maxFanout, 0.0f);
    }

    public GraphReasoner(JdbcTemplate jdbcTemplate, int maxFanout, float minRelationTrust) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        if (maxFanout <= 0) {
            throw new IllegalArgumentException("图联想 fanout 必须大于 0: " + maxFanout);
        }
        if (!isProbability(minRelationTrust)) {
            throw new IllegalArgumentException("关系最低可信分必须在 [0,1] 范围内: " + minRelationTrust);
        }
        this.maxFanout = maxFanout;
        this.minRelationTrust = minRelationTrust;
    }

    /** 一条关系边（含方向与强度）。 */
    private record Edge(String relationType, String sourceId, String targetId, float strength) {
        Edge {
            requireText(relationType, "图推理关系类型");
            requireEntityId(sourceId, "图推理关系 sourceId");
            requireEntityId(targetId, "图推理关系 targetId");
            requireProbability(strength, "图推理关系强度");
        }
    }

    /** 联想路径的一跳。 */
    public record Hop(String fromId, String relationType, String toId, String toName, float strength) {
        public Hop {
            requireEntityId(fromId, "联想路径 fromId");
            requireText(relationType, "联想路径关系类型");
            requireEntityId(toId, "联想路径 toId");
            requireText(toName, "联想路径目标名称");
            requireProbability(strength, "联想路径关系强度");
        }
    }

    /** 从起点实体出发的联想路径（有序多跳）。 */
    public record AssociationPath(List<Hop> hops) {
        public AssociationPath {
            hops = List.copyOf(Objects.requireNonNull(hops, "联想路径 hops 不能为空"));
        }
        @Nullable
        public String endId() {
            return hops.isEmpty() ? null : hops.get(hops.size() - 1).toId();
        }
        public int depth() {
            return hops.size();
        }
        /** 人类可读标签：A --关系--> B --关系--> C。 */
        public List<String> labels() {
            var out = new ArrayList<String>();
            for (var h : hops) {
                out.add("--" + h.relationType() + "--> " + h.toName());
            }
            return out;
        }
    }

    /** 连接机会：start 经 bridge 两跳可达 to，但 start 与 to 无直接 ACTIVE 边。 */
    public record ConnectionOpportunity(String fromId, String bridgeId, String bridgeName,
                                        String toId, String toName,
                                        String firstRelation, String secondRelation, float score) {
        public ConnectionOpportunity {
            requireEntityId(fromId, "连接机会 fromId");
            requireEntityId(bridgeId, "连接机会 bridgeId");
            requireText(bridgeName, "连接机会 bridgeName");
            requireEntityId(toId, "连接机会 toId");
            requireText(toName, "连接机会 toName");
            requireText(firstRelation, "连接机会第一跳关系");
            requireText(secondRelation, "连接机会第二跳关系");
            requireProbability(score, "连接机会分数");
        }
    }

    /**
     * 从起点实体出发的带关系类型多跳路径（深度 ≤ maxDepth，去环，最短路径优先）。
     */
    public List<AssociationPath> pathsFrom(String entityId, int maxDepth, @Nullable MemoryReadFilter filter) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("图联想起点实体 id 不能为空");
        }
        if (!entityId.equals(entityId.trim())) {
            throw new IllegalArgumentException("图联想起点实体 id 不能包含首尾空白: " + entityId);
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("图联想最大深度必须大于 0: " + maxDepth);
        }
        List<AssociationPath> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(entityId);
        // 当前层：节点 id → 到达该节点的路径
        Map<String, AssociationPath> frontier = new LinkedHashMap<>();
        frontier.put(entityId, new AssociationPath(List.of()));

        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            var edges = loadEdges(frontier.keySet());
            var nameMap = loadRecallableNames(collectNeighborIds(edges, frontier.keySet()), filter);
            Map<String, AssociationPath> next = new LinkedHashMap<>();
            Map<String, Integer> fanoutUsed = new java.util.HashMap<>();
            for (var edge : edges) {
                // 确定 frontier 端与 neighbor 端
                String fromId = null;
                String toId = null;
                if (frontier.containsKey(edge.sourceId())) {
                    fromId = edge.sourceId();
                    toId = edge.targetId();
                } else if (frontier.containsKey(edge.targetId())) {
                    fromId = edge.targetId();
                    toId = edge.sourceId();
                }
                if (fromId == null || toId == null) continue;
                if (visited.contains(toId) || next.containsKey(toId)) continue;
                String toName = nameMap.get(toId);
                if (toName == null) continue;  // 邻居不可召回 / 被空间过滤
                int used = fanoutUsed.getOrDefault(fromId, 0);
                if (used >= maxFanout) continue;
                fanoutUsed.put(fromId, used + 1);

                var basePath = frontier.get(fromId);
                var hops = new ArrayList<>(basePath.hops());
                hops.add(new Hop(fromId, edge.relationType(), toId, toName, edge.strength()));
                var path = new AssociationPath(hops);
                next.put(toId, path);
                result.add(path);
            }
            next.keySet().forEach(visited::add);
            frontier = next;
        }
        log.debug("图推理: pathsFrom entityId={}, maxDepth={}, paths={}", entityId, maxDepth, result.size());
        return result;
    }

    /**
     * 连接机会发现：start 经某桥实体两跳可达 to，但 start 与 to 之间无直接 ACTIVE 边。
     */
    public List<ConnectionOpportunity> connectionOpportunities(String entityId, @Nullable MemoryReadFilter filter) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("图联想起点实体 id 不能为空");
        }
        if (!entityId.equals(entityId.trim())) {
            throw new IllegalArgumentException("图联想起点实体 id 不能包含首尾空白: " + entityId);
        }
        // 1 跳：直接邻居 + 连接关系/强度
        var startEdges = loadEdges(Set.of(entityId));
        Map<String, Edge> directNeighbors = new LinkedHashMap<>();  // neighborId → start→neighbor 边
        int startFanout = 0;
        for (var e : startEdges) {
            String n = neighborOf(e, entityId);
            if (n == null || n.equals(entityId)) continue;
            if (startFanout++ >= maxFanout) break;
            directNeighbors.putIfAbsent(n, e);
        }
        if (directNeighbors.isEmpty()) {
            return List.of();
        }
        // 2 跳：邻居的邻居
        var bridgeEdges = loadEdges(directNeighbors.keySet());
        // 候选 to id 收集 + 名称解析（含 bridge）
        Set<String> idsToName = new LinkedHashSet<>(directNeighbors.keySet());
        for (var e : bridgeEdges) {
            idsToName.add(e.sourceId());
            idsToName.add(e.targetId());
        }
        var nameMap = loadRecallableNames(idsToName, filter);

        Map<String, ConnectionOpportunity> best = new LinkedHashMap<>();
        Map<String, Integer> bridgeFanout = new java.util.HashMap<>();
        for (var e : bridgeEdges) {
            // 找出 e 中属于直接邻居的一端作为 bridge，另一端作为候选 to
            String bridge = null;
            String to = null;
            if (directNeighbors.containsKey(e.sourceId())) {
                bridge = e.sourceId();
                to = e.targetId();
            } else if (directNeighbors.containsKey(e.targetId())) {
                bridge = e.targetId();
                to = e.sourceId();
            }
            if (bridge == null || to == null) continue;
            if (to.equals(entityId) || directNeighbors.containsKey(to)) continue;  // 排除起点与直接邻居（已有直接边）
            String toName = nameMap.get(to);
            String bridgeName = nameMap.get(bridge);
            if (toName == null || bridgeName == null) continue;
            int used = bridgeFanout.getOrDefault(bridge, 0);
            if (used >= maxFanout) continue;
            bridgeFanout.put(bridge, used + 1);

            Edge firstHop = directNeighbors.get(bridge);
            float score = (float) Math.sqrt(firstHop.strength() * e.strength());
            var opp = new ConnectionOpportunity(
                    entityId, bridge, bridgeName, to, toName,
                    firstHop.relationType(), e.relationType(), score);
            var existing = best.get(to);
            if (existing == null || opp.score() > existing.score()) {
                best.put(to, opp);
            }
        }
        var result = new ArrayList<>(best.values());
        result.sort((a, b) -> Float.compare(b.score(), a.score()));
        log.debug("图推理: connectionOpportunities entityId={}, opportunities={}", entityId, result.size());
        return result;
    }

    // ── 内部辅助 ──

    @Nullable
    private String neighborOf(Edge e, String node) {
        if (e.sourceId().equals(node)) return e.targetId();
        if (e.targetId().equals(node)) return e.sourceId();
        return null;
    }

    private Set<String> collectNeighborIds(List<Edge> edges, Set<String> frontier) {
        Set<String> ids = new LinkedHashSet<>();
        for (var e : edges) {
            if (frontier.contains(e.sourceId())) ids.add(e.targetId());
            if (frontier.contains(e.targetId())) ids.add(e.sourceId());
        }
        return ids;
    }

    /** 加载触及给定节点集合的所有当前有效关系边。 */
    private List<Edge> loadEdges(Collection<String> nodeIds) {
        Objects.requireNonNull(nodeIds, "图推理节点集合不能为空");
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        for (String nodeId : nodeIds) {
            requireEntityId(nodeId, "图推理节点 id");
        }
        String placeholders = buildPlaceholders(nodeIds.size());
        String trustClause = minRelationTrust > 0.0f ? " AND mr.trust_score >= ?" : "";
        String sql = """
                SELECT tr.relation_type AS relation_type,
                       tr.source_entity_id AS source_id,
                       tr.target_entity_id AS target_id,
                       mrv.strength AS strength
                FROM temporal_relations tr
                JOIN memory_relations mr ON mr.id = tr.id AND mr.status = 'ACTIVE'
                LEFT JOIN memory_relation_versions mrv ON mrv.relation_id = tr.id AND mrv.is_current = 1
                WHERE tr.valid_to IS NULL
                  AND (tr.source_entity_id IN (%s) OR tr.target_entity_id IN (%s))%s
                """.formatted(placeholders, placeholders, trustClause);
        List<Object> params = new ArrayList<>();
        params.addAll(nodeIds);
        params.addAll(nodeIds);
        if (minRelationTrust > 0.0f) {
            params.add(minRelationTrust);
        }
        return requireEdges(jdbcTemplate.query(sql, (rs, n) -> {
            Object strengthValue = rs.getObject("strength");
            if (strengthValue == null) {
                throw new IllegalStateException("图推理关系版本缺少 strength: source=%s, target=%s, relationType=%s"
                        .formatted(
                                rs.getString("source_id"),
                                rs.getString("target_id"),
                                rs.getString("relation_type")));
            }
            float strength = rs.getFloat("strength");
            return new Edge(rs.getString("relation_type"), rs.getString("source_id"),
                    rs.getString("target_id"), strength);
        }, params.toArray()), "图推理边查询结果");
    }

    /** 解析候选节点 id → name，仅保留可召回生命周期且通过空间过滤的当前实体。 */
    private Map<String, String> loadRecallableNames(Set<String> ids, @Nullable MemoryReadFilter filter) {
        Objects.requireNonNull(ids, "图推理候选节点集合不能为空");
        if (ids.isEmpty()) {
            return Map.of();
        }
        for (String id : ids) {
            requireEntityId(id, "图推理候选节点 id");
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, name FROM temporal_entities WHERE is_current = 1"
                        + " AND lifecycle_state IN " + com.lifepilot.memory.governance.lifecycle.LifecycleState.recallableSqlInClause()
                        // 排除系统派生聚合实体（如 __consolidated_profile）——它们连接到一切，
                        // 作为桥/端点只会制造噪声而非真实联想
                        + " AND name NOT LIKE '\\_\\_%' ESCAPE '\\'"
                        + " AND id IN (" + buildPlaceholders(ids.size()) + ")");
        List<Object> params = new ArrayList<>(ids);
        if (filter != null && !filter.isUnrestricted()) {
            if (filter.restrictsSpaces()) {
                sql.append(" AND space_id IN (").append(buildPlaceholders(filter.spaceIds().size())).append(")");
                params.addAll(filter.spaceIds());
            }
            if (filter.restrictsScopes()) {
                sql.append(" AND memory_scope IN (").append(buildPlaceholders(filter.scopes().size())).append(")");
                params.addAll(filter.scopes().stream().map(Enum::name).toList());
            }
        }
        Map<String, String> map = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            String id = rs.getString("id");
            String name = rs.getString("name");
            requireEntityId(id, "图推理候选实体 id");
            requireText(name, "图推理候选实体名称");
            map.put(id, name);
        }, params.toArray());
        return map;
    }

    private String buildPlaceholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private List<Edge> requireEdges(List<Edge> edges, String label) {
        if (edges == null) {
            throw new IllegalStateException(label + "不能为空");
        }
        if (edges.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(label + "包含 null 条目");
        }
        return edges;
    }

    private static void requireEntityId(String value, String name) {
        requireText(value, name);
        if (!value.equals(value.trim())) {
            throw new IllegalStateException(name + "不能包含首尾空白: " + value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "不能为空");
        }
    }

    private static void requireProbability(float value, String name) {
        if (!isProbability(value)) {
            throw new IllegalStateException(name + "必须在 [0,1] 范围内: " + value);
        }
    }

    private static boolean isProbability(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
