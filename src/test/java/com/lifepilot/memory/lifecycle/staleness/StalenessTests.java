package com.lifepilot.memory.lifecycle.staleness;

import com.lifepilot.agent.learning.staleness.*;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staleness 组件单元测试集合。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("Staleness 组件单元测试")
class StalenessTests {

    @Nested
    @DisplayName("VectorBasedStaleConflictDetector")
    class Detector {

        @Test
        @DisplayName("类型白名单外 → 空列表")
        void 类型白名单外_跳过() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            VectorSearcher vec = mock(VectorSearcher.class);
            SemanticMemory mem = mock(SemanticMemory.class);
            VectorBasedStaleConflictDetector det =
                    new VectorBasedStaleConflictDetector(vec, mem, cfg);

            TemporalEntity e = newEntity("e1", EntityType.PERSON, "Alice 是博士",
                    LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            assertThat(det.findStaleNeighbors(e)).isEmpty();
            verify(vec, never()).searchEntities(anyString(), anyInt(), anyFloat());
        }

        @Test
        @DisplayName("描述过短 → 空列表")
        void 描述过短_跳过() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setDetectableTypes(Set.of("PREFERENCE"));
            VectorSearcher vec = mock(VectorSearcher.class);
            SemanticMemory mem = mock(SemanticMemory.class);
            VectorBasedStaleConflictDetector det =
                    new VectorBasedStaleConflictDetector(vec, mem, cfg);

            TemporalEntity e = newEntity("e1", EntityType.PREFERENCE, "",
                    LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            assertThat(det.findStaleNeighbors(e)).isEmpty();
        }

        @Test
        @DisplayName("UNVERIFIED 邻居被过滤")
        void UNVERIFIED_邻居过滤() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setDetectableTypes(Set.of("PREFERENCE"));
            cfg.setMaxNeighborsPerDetection(3);
            VectorSearcher vec = mock(VectorSearcher.class);
            SemanticMemory mem = mock(SemanticMemory.class);

            when(vec.searchEntities(anyString(), anyInt(), anyFloat()))
                    .thenReturn(List.of(
                            new VectorSearchResult("nb1", 0.95f),
                            new VectorSearchResult("nb2", 0.91f)));
            when(mem.findById("nb1")).thenReturn(Optional.of(
                    newEntity("nb1", EntityType.PREFERENCE, "旧偏好 A",
                            LifecycleState.ACTIVE, MemoryTrustLevel.UNVERIFIED)));
            when(mem.findById("nb2")).thenReturn(Optional.of(
                    newEntity("nb2", EntityType.PREFERENCE, "旧偏好 B",
                            LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT)));

            VectorBasedStaleConflictDetector det =
                    new VectorBasedStaleConflictDetector(vec, mem, cfg);

            TemporalEntity e = newEntity("new1", EntityType.PREFERENCE,
                    "用户现在喜欢绿茶了", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);

            List<TemporalEntity> stale = det.findStaleNeighbors(e);
            assertThat(stale).hasSize(1);
            assertThat(stale.get(0).id()).isEqualTo("nb2");
        }

        @Test
        @DisplayName("自身 ID 排除")
        void 自身ID_排除() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setDetectableTypes(Set.of("PREFERENCE"));
            VectorSearcher vec = mock(VectorSearcher.class);
            SemanticMemory mem = mock(SemanticMemory.class);

            when(vec.searchEntities(anyString(), anyInt(), anyFloat()))
                    .thenReturn(List.of(new VectorSearchResult("self", 0.99f)));

            VectorBasedStaleConflictDetector det =
                    new VectorBasedStaleConflictDetector(vec, mem, cfg);

            TemporalEntity e = newEntity("self", EntityType.PREFERENCE, "用户喜欢绿茶",
                    LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            assertThat(det.findStaleNeighbors(e)).isEmpty();
        }

        @Test
        @DisplayName("max-neighbors 截断")
        void max截断() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setDetectableTypes(Set.of("PREFERENCE"));
            cfg.setMaxNeighborsPerDetection(1);
            VectorSearcher vec = mock(VectorSearcher.class);
            SemanticMemory mem = mock(SemanticMemory.class);
            when(vec.searchEntities(anyString(), anyInt(), anyFloat()))
                    .thenReturn(List.of(
                            new VectorSearchResult("nb1", 0.99f),
                            new VectorSearchResult("nb2", 0.98f)));
            when(mem.findById(anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                return Optional.of(newEntity(id, EntityType.PREFERENCE, "邻居 " + id,
                        LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            });

            VectorBasedStaleConflictDetector det =
                    new VectorBasedStaleConflictDetector(vec, mem, cfg);
            TemporalEntity e = newEntity("new", EntityType.PREFERENCE, "新偏好长文本",
                    LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            List<TemporalEntity> stale = det.findStaleNeighbors(e);
            assertThat(stale).hasSize(1);
            assertThat(stale.get(0).id()).isEqualTo("nb1");
        }
    }

    @Nested
    @DisplayName("StalenessMarker")
    class Marker {

        @Test
        @DisplayName("ACTIVE 邻居 → 调用 updateLifecycleState 一次")
        void active_邻居_标记成功() {
            SemanticMemory mem = mock(SemanticMemory.class);
            StalenessMarker marker = new StalenessMarker(mem);

            TemporalEntity nb = newEntity("nb1", EntityType.PREFERENCE, "旧偏好",
                    LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            int count = marker.markAsStale("trigger-1", List.of(nb));
            assertThat(count).isEqualTo(1);
            verify(mem).updateLifecycleState(eq("nb1"),
                    eq(LifecycleState.STALE_CANDIDATE),
                    eq("stale-by:trigger-1"),
                    eq(ChangeSource.LLM_SEMANTIC));
        }

        @Test
        @DisplayName("已 ARCHIVED 邻居 → 静默跳过")
        void archived_邻居_跳过() {
            SemanticMemory mem = mock(SemanticMemory.class);
            StalenessMarker marker = new StalenessMarker(mem);

            TemporalEntity nb = newEntity("nb1", EntityType.PREFERENCE, "旧偏好",
                    LifecycleState.ARCHIVED, MemoryTrustLevel.EXPLICIT);
            assertThat(marker.markAsStale("trigger", List.of(nb))).isZero();
            verify(mem, never()).updateLifecycleState(anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("某条更新抛异常 → 继续下一条")
        void 异常_不中断() {
            SemanticMemory mem = mock(SemanticMemory.class);
            doThrow(new RuntimeException("db err"))
                    .when(mem).updateLifecycleState(eq("nb1"), any(), anyString(), any());
            StalenessMarker marker = new StalenessMarker(mem);

            List<TemporalEntity> list = List.of(
                    newEntity("nb1", EntityType.PREFERENCE, "A", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT),
                    newEntity("nb2", EntityType.PREFERENCE, "B", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT)
            );
            int count = marker.markAsStale("trig", list);
            assertThat(count).isEqualTo(1);
            verify(mem, times(2)).updateLifecycleState(anyString(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("NeighborRefreshService")
    class Refresh {

        @Test
        @DisplayName("开关关闭 → 不触发")
        void 关闭_不触发() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setNeighborRefreshEnabled(false);
            NeighborRefreshService svc = new NeighborRefreshService(cfg);

            int n = svc.writeRefreshCandidates(
                    newEntity("t", EntityType.PREFERENCE, "新", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT),
                    List.of(newEntity("n", EntityType.PREFERENCE, "旧", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT)));
            assertThat(n).isZero();
        }

        @Test
        @DisplayName("开启 + 非空邻居 → 对每个邻居记数")
        void 开启_计数正确() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setNeighborRefreshEnabled(true);
            NeighborRefreshService svc = new NeighborRefreshService(cfg);

            int n = svc.writeRefreshCandidates(
                    newEntity("t", EntityType.PREFERENCE, "新", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT),
                    List.of(
                            newEntity("n1", EntityType.PREFERENCE, "旧1", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT),
                            newEntity("n2", EntityType.PREFERENCE, "旧2", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT)
                    ));
            assertThat(n).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("StalenessCoordinator")
    class Coordinator {

        @Test
        @DisplayName("enabled=false → 不调用 detector")
        void 关闭_不执行() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setEnabled(false);
            AtomicInteger detectCalls = new AtomicInteger();
            StaleConflictDetector detector = e -> { detectCalls.incrementAndGet(); return List.of(); };
            StalenessMarker marker = mock(StalenessMarker.class);
            NeighborRefreshService refresh = mock(NeighborRefreshService.class);
            StalenessCoordinator c = new StalenessCoordinator(detector, marker, refresh, cfg);

            c.processSync(newEntity("x", EntityType.PREFERENCE, "desc len ok", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            assertThat(detectCalls.get()).isZero();
        }

        @Test
        @DisplayName("detector 返回空 → 不调用 marker 和 refresh")
        void 空邻居_跳过后续() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            StaleConflictDetector detector = e -> List.of();
            StalenessMarker marker = mock(StalenessMarker.class);
            NeighborRefreshService refresh = mock(NeighborRefreshService.class);
            StalenessCoordinator c = new StalenessCoordinator(detector, marker, refresh, cfg);

            c.processSync(newEntity("x", EntityType.PREFERENCE, "desc ok", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            verify(marker, never()).markAsStale(anyString(), any());
            verify(refresh, never()).writeRefreshCandidates(any(), any());
        }

        @Test
        @DisplayName("detector 抛异常 → 不调用 marker")
        void detector异常_隔离() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            StaleConflictDetector detector = e -> { throw new RuntimeException("boom"); };
            StalenessMarker marker = mock(StalenessMarker.class);
            NeighborRefreshService refresh = mock(NeighborRefreshService.class);
            StalenessCoordinator c = new StalenessCoordinator(detector, marker, refresh, cfg);

            c.processSync(newEntity("x", EntityType.PREFERENCE, "desc ok", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            verify(marker, never()).markAsStale(anyString(), any());
            verify(refresh, never()).writeRefreshCandidates(any(), any());
        }

        @Test
        @DisplayName("marker 异常 → refresh 不触发")
        void marker异常_refresh不触发() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            TemporalEntity nb = newEntity("nb", EntityType.PREFERENCE, "x", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            StaleConflictDetector detector = e -> List.of(nb);
            StalenessMarker marker = mock(StalenessMarker.class);
            when(marker.markAsStale(anyString(), any())).thenThrow(new RuntimeException("x"));
            NeighborRefreshService refresh = mock(NeighborRefreshService.class);
            StalenessCoordinator c = new StalenessCoordinator(detector, marker, refresh, cfg);

            c.processSync(newEntity("x", EntityType.PREFERENCE, "desc ok", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            verify(refresh, never()).writeRefreshCandidates(any(), any());
        }

        @Test
        @DisplayName("全流程成功")
        void 流程成功() {
            AgentLearningProperties.Staleness cfg = new AgentLearningProperties.Staleness();
            cfg.setNeighborRefreshEnabled(true);
            TemporalEntity nb = newEntity("nb", EntityType.PREFERENCE, "x", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT);
            StaleConflictDetector detector = e -> List.of(nb);
            StalenessMarker marker = mock(StalenessMarker.class);
            when(marker.markAsStale(anyString(), any())).thenReturn(1);
            NeighborRefreshService refresh = new NeighborRefreshService(cfg);
            StalenessCoordinator c = new StalenessCoordinator(detector, marker, refresh, cfg);

            c.processSync(newEntity("t", EntityType.PREFERENCE, "desc ok", LifecycleState.ACTIVE, MemoryTrustLevel.EXPLICIT));
            verify(marker, times(1)).markAsStale(eq("t"), any());
        }
    }

    // ---------------------------------------------------------------------

    private static TemporalEntity newEntity(String id,
                                            EntityType type,
                                            String description,
                                            LifecycleState state,
                                            MemoryTrustLevel trust) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id, type, id, description, Map.of(),
                1, true, now, null, null,
                0.8f, 0.5f, 0, null, now, now,
                state, null, null, Temporality.PERSISTENT,
                null, false, List.of(),
                MemoryEvidenceKind.USER_EXPLICIT, trust,
                0.85f, 1, null);
    }
}
