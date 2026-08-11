package com.lifepilot.memory.mcp.server;

import com.lifepilot.mcp.protocol.JsonRpcMessage;
import com.lifepilot.memory.governance.config.MemoryGovernanceProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.governance.server.MemoryMcpHandler;
import com.lifepilot.memory.governance.server.MemoryMcpToolRegistry;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MemoryMcpHandler 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class MemoryMcpHandler_单元测试 {

    private final MemoryGovernanceProperties properties = new MemoryGovernanceProperties();
    private final MemoryMcpToolRegistry registry = new MemoryMcpToolRegistry();

    @Test
    void initialize_返回serverInfo() {
        var handler = handler();
        var req = JsonRpcMessage.request(1L, "initialize", Map.of());

        var resp = handler.handle(req);

        assertThat(resp.error()).isNull();
        assertThat(resp.result()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        assertThat(result).containsKeys("protocolVersion", "serverInfo", "capabilities");
    }

    @Test
    void tools_list_返回3个工具() {
        var handler = handler();
        var req = JsonRpcMessage.request(2L, "tools/list", Map.of());

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(3);
        assertThat(tools).extracting(t -> t.get("name"))
                .containsExactly("memory_search", "memory_recall", "memory_create");
    }

    @Test
    void tools_call_search_返回结果() {
        var hybrid = mock(HybridRetriever.class);
        when(hybrid.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(retrieval("e1", "目标一", 0.9f)));
        var handler = handler(hybrid, mock(EpisodicMemory.class), mock(SemanticMemory.class));

        var req = JsonRpcMessage.request(3L, "tools/call", Map.of(
                "name", "memory_search",
                "arguments", Map.of("query", "目标", "topK", 5)
        ));

        var resp = handler.handle(req);

        assertThat(resp.error()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.result();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        assertThat(structured.get("count")).isEqualTo(1);
    }

    @Test
    void 构造时缺episodicMemory应失败() {
        assertThatThrownBy(() -> new MemoryMcpHandler(
                registry,
                properties,
                mock(HybridRetriever.class),
                null,
                mock(SemanticMemory.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("episodicMemory");
    }

    @Test
    void tools_call_recall_返回对话() {
        var ep = mock(EpisodicMemory.class);
        Instant now = Instant.now();
        when(ep.search(anyString())).thenReturn(List.of(new ConversationRecord(
                "c1", "s1", "goal", "summary", List.<MessageRecord>of(), now, now
        )));
        var handler = handler(mock(HybridRetriever.class), ep, mock(SemanticMemory.class));

        var req = JsonRpcMessage.request(5L, "tools/call", Map.of(
                "name", "memory_recall",
                "arguments", Map.of("query", "goal", "limit", 1)
        ));

        var resp = handler.handle(req);
        assertThat(resp.error()).isNull();
    }

    @Test
    void tools_call_create_invalidType返回错误() {
        var sem = mock(SemanticMemory.class);
        var handler = handler(mock(HybridRetriever.class), mock(EpisodicMemory.class), sem);

        var req = JsonRpcMessage.request(6L, "tools/call", Map.of(
                "name", "memory_create",
                "arguments", Map.of("name", "test", "entityType", "INVALID_TYPE")
        ));

        var resp = handler.handle(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
    }

    @Test
    void create_entityType必须使用精确枚举名不做大小写兼容() {
        var sem = mock(SemanticMemory.class);
        var handler = handler(mock(HybridRetriever.class), mock(EpisodicMemory.class), sem);

        var req = JsonRpcMessage.request(61L, "tools/call", Map.of(
                "name", "memory_create",
                "arguments", Map.of("name", "学习 Rust", "entityType", "goal")
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("Invalid entityType: goal");
    }

    @Test
    void create_name带首尾空白时返回invalidParams且不写入() {
        var sem = mock(SemanticMemory.class);
        var handler = handler(mock(HybridRetriever.class), mock(EpisodicMemory.class), sem);

        var req = JsonRpcMessage.request(62L, "tools/call", Map.of(
                "name", "memory_create",
                "arguments", Map.of("name", " 学习 Rust", "entityType", "GOAL")
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("name must not contain leading or trailing whitespace");
        org.mockito.Mockito.verify(sem, org.mockito.Mockito.never())
                .upsertWithConflictDetection(any(TemporalEntity.class), nullable(String.class), any(MemoryWriteContext.class));
    }

    @Test
    void tools_call_create_成功路径() {
        var sem = mock(SemanticMemory.class);
        Instant now = Instant.now();
        when(sem.upsertWithConflictDetection(
                any(TemporalEntity.class),
                nullable(String.class),
                any(MemoryWriteContext.class)))
                .thenReturn(new TemporalEntity(
                        "created-id", EntityType.GOAL, "学习 Rust", "描述",
                        Map.of(), 1, true, now, null, null,
                        0.8f, 0.5f, 0, null, now, now,
                                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                                null,
                                null,
                                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                                null,
                                false,
                                java.util.List.of(),
                                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                                1.0f,
                                1,
                                now));
        var handler = handler(mock(HybridRetriever.class), mock(EpisodicMemory.class), sem);

        var req = JsonRpcMessage.request(7L, "tools/call", Map.of(
                "name", "memory_create",
                "arguments", Map.of("name", "学习 Rust", "entityType", "GOAL")
        ));

        var resp = handler.handle(req);
        assertThat(resp.error()).isNull();
    }

    @Test
    void tools_call_unknown工具_返回invalidParams() {
        var handler = handler();
        var req = JsonRpcMessage.request(8L, "tools/call", Map.of(
                "name", "unknown_tool",
                "arguments", Map.of()
        ));
        var resp = handler.handle(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
    }

    @Test
    void tools_call_arguments非对象时返回invalidParams且不执行工具() {
        var hybrid = mock(HybridRetriever.class);
        var handler = handler(hybrid, mock(EpisodicMemory.class), mock(SemanticMemory.class));
        var req = JsonRpcMessage.request(81L, "tools/call", Map.of(
                "name", "memory_search",
                "arguments", "not-object"
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("arguments must be an object");
        org.mockito.Mockito.verify(hybrid, org.mockito.Mockito.never())
                .retrieve(anyString(), anyInt(), any());
    }

    @Test
    void 未知method_返回methodNotFound() {
        var handler = handler();
        var req = JsonRpcMessage.request(9L, "foo/bar", Map.of());
        var resp = handler.handle(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32601);
    }

    @Test
    void 缺少method_invalidRequest() {
        var handler = handler();
        var req = new JsonRpcMessage("2.0", 10L, null, null, null, null);
        var resp = handler.handle(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32600);
    }

    @Test
    void search_缺少query_invalidParams() {
        var hybrid = mock(HybridRetriever.class);
        var handler = handler(hybrid, mock(EpisodicMemory.class), mock(SemanticMemory.class));
        var req = JsonRpcMessage.request(11L, "tools/call", Map.of(
                "name", "memory_search",
                "arguments", Map.of()
        ));
        var resp = handler.handle(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
    }

    @Test
    void search_topK非法时返回invalidParams且不检索() {
        var hybrid = mock(HybridRetriever.class);
        var handler = handler(hybrid, mock(EpisodicMemory.class), mock(SemanticMemory.class));
        var req = JsonRpcMessage.request(12L, "tools/call", Map.of(
                "name", "memory_search",
                "arguments", Map.of("query", "目标", "topK", 0)
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("topK must be a positive integer");
        org.mockito.Mockito.verify(hybrid, org.mockito.Mockito.never())
                .retrieve(anyString(), anyInt(), any());
    }

    @Test
    void search_query带首尾空白时返回invalidParams且不检索() {
        var hybrid = mock(HybridRetriever.class);
        var handler = handler(hybrid, mock(EpisodicMemory.class), mock(SemanticMemory.class));
        var req = JsonRpcMessage.request(121L, "tools/call", Map.of(
                "name", "memory_search",
                "arguments", Map.of("query", " 目标", "topK", 5)
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("query must not contain leading or trailing whitespace");
        org.mockito.Mockito.verify(hybrid, org.mockito.Mockito.never())
                .retrieve(anyString(), anyInt(), any());
    }

    @Test
    void recall_limit类型非法时返回invalidParams() {
        var ep = mock(EpisodicMemory.class);
        var handler = handler(mock(HybridRetriever.class), ep, mock(SemanticMemory.class));
        var req = JsonRpcMessage.request(13L, "tools/call", Map.of(
                "name", "memory_recall",
                "arguments", Map.of("query", "目标", "limit", "abc")
        ));

        var resp = handler.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.error();
        assertThat(err.get("code")).isEqualTo(-32602);
        assertThat(err.get("message")).isEqualTo("limit must be a positive integer");
        org.mockito.Mockito.verify(ep, org.mockito.Mockito.never()).search(anyString());
    }

    private RetrievalResult retrieval(String id, String name, float score) {
        return new RetrievalResult(id, "GOAL", name, "描述", score,
                new RetrievalResult.ScoreBreakdown(score, score * 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                "vector", null, 0.5f, null, false, false, false);
    }

    private MemoryMcpHandler handler() {
        return handler(
                mock(HybridRetriever.class),
                mock(EpisodicMemory.class),
                mock(SemanticMemory.class));
    }

    private MemoryMcpHandler handler(HybridRetriever hybridRetriever,
                                     EpisodicMemory episodicMemory,
                                     SemanticMemory semanticMemory) {
        return new MemoryMcpHandler(
                registry,
                properties,
                hybridRetriever,
                episodicMemory,
                semanticMemory);
    }
}
