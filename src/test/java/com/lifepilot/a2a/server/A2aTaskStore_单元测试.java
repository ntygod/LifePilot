package com.lifepilot.a2a.server;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2aTaskStore 单元测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
class A2aTaskStore_单元测试 {

    private A2aTaskStore store;
    private A2aProperties properties;

    @BeforeEach
    void setUp() {
        properties = new A2aProperties();
        properties.getTask().setMaxHistoryLength(5);
        properties.getTask().setTtlMinutes(1);
        store = new A2aTaskStore(properties);
    }

    private A2aMessage 创建测试消息(String messageId, String contextId) {
        return new A2aMessage(messageId, A2aRole.USER,
                List.of(new A2aPart.Text("测试内容", null)),
                null, contextId, null);
    }

    private A2aMessage 创建带TaskId的消息(String messageId, String taskId) {
        return new A2aMessage(messageId, A2aRole.USER,
                List.of(new A2aPart.Text("追加内容", null)),
                taskId, null, null);
    }

    // ── 创建 ──

    @Test
    void create_生成唯一taskId和SUBMITTED状态() {
        var message = 创建测试消息("msg-1", "ctx-1");
        A2aTask task = store.create(message);

        assertThat(task.id()).isNotBlank();
        assertThat(task.status().state()).isEqualTo(A2aTaskState.SUBMITTED);
        assertThat(task.contextId()).isEqualTo("ctx-1");
        assertThat(task.history()).hasSize(1);
    }

    @Test
    void create_消息无contextId时自动生成() {
        var message = 创建测试消息("msg-1", null);
        A2aTask task = store.create(message);

        assertThat(task.contextId()).isNotBlank();
    }

    @Test
    void create_两次创建生成不同taskId() {
        var task1 = store.create(创建测试消息("msg-1", null));
        var task2 = store.create(创建测试消息("msg-2", null));

        assertThat(task1.id()).isNotEqualTo(task2.id());
    }

    // ── 解析或创建 ──

    @Test
    void resolveOrCreate_已有taskId追加history() {
        var task = store.create(创建测试消息("msg-1", "ctx-1"));
        var followUp = 创建带TaskId的消息("msg-2", task.id());

        A2aTask resolved = store.resolveOrCreate(followUp);

        assertThat(resolved.id()).isEqualTo(task.id());
        assertThat(resolved.history()).hasSize(2);
    }

    @Test
    void resolveOrCreate_无taskId创建新Task() {
        var message = 创建测试消息("msg-1", null);
        A2aTask task = store.resolveOrCreate(message);

        assertThat(task.status().state()).isEqualTo(A2aTaskState.SUBMITTED);
    }

    @Test
    void resolveOrCreate_不存在的taskId创建新Task() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("test", null)),
                "nonexistent-task-id", null, null);
        A2aTask task = store.resolveOrCreate(message);

        assertThat(task.id()).isNotEqualTo("nonexistent-task-id");
        assertThat(task.status().state()).isEqualTo(A2aTaskState.SUBMITTED);
    }

    // ── 查找 ──

    @Test
    void find_存在的taskId返回Task() {
        var task = store.create(创建测试消息("msg-1", null));
        assertThat(store.find(task.id())).isPresent();
    }

    @Test
    void find_不存在的taskId返回空() {
        assertThat(store.find("nonexistent")).isEmpty();
    }

    // ── 按 contextId 列表 ──

    @Test
    void listByContextId_返回同contextId的所有Task() {
        store.create(创建测试消息("msg-1", "ctx-shared"));
        store.create(创建测试消息("msg-2", "ctx-shared"));
        store.create(创建测试消息("msg-3", "ctx-other"));

        assertThat(store.listByContextId("ctx-shared")).hasSize(2);
        assertThat(store.listByContextId("ctx-other")).hasSize(1);
    }

    // ── 状态更新 ──

    @Test
    void updateStatus_更新状态和时间戳() {
        var task = store.create(创建测试消息("msg-1", null));

        A2aTask updated = store.updateStatus(task.id(), A2aTaskState.WORKING, null);

        assertThat(updated.status().state()).isEqualTo(A2aTaskState.WORKING);
        assertThat(updated.status().timestamp()).isNotBlank();
    }

    @Test
    void updateStatus_附带消息_包含Agent角色状态消息() {
        var task = store.create(创建测试消息("msg-1", null));

        A2aTask updated = store.updateStatus(task.id(), A2aTaskState.FAILED, "执行超时");

        assertThat(updated.status().state()).isEqualTo(A2aTaskState.FAILED);
        assertThat(updated.status().message()).isNotNull();
        assertThat(updated.status().message().role()).isEqualTo(A2aRole.AGENT);
    }

    @Test
    void updateStatus_不存在的taskId抛出异常() {
        assertThatThrownBy(() -> store.updateStatus("nonexistent", A2aTaskState.WORKING, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task 不存在");
    }

    // ── Artifact ──

    @Test
    void addArtifact_追加到已有列表() {
        var task = store.create(创建测试消息("msg-1", null));
        var artifact = new A2aArtifact("art-1", List.of(new A2aPart.Text("结果", null)), null, null);

        A2aTask updated = store.addArtifact(task.id(), artifact);

        assertThat(updated.artifacts()).hasSize(1);
        assertThat(updated.artifacts().getFirst().artifactId()).isEqualTo("art-1");
    }

    @Test
    void addArtifact_多次追加() {
        var task = store.create(创建测试消息("msg-1", null));
        store.addArtifact(task.id(), new A2aArtifact("art-1", List.of(new A2aPart.Text("a", null)), null, null));
        A2aTask updated = store.addArtifact(task.id(), new A2aArtifact("art-2", List.of(new A2aPart.Text("b", null)), null, null));

        assertThat(updated.artifacts()).hasSize(2);
    }

    // ── History ──

    @Test
    void appendHistory_超出maxLength时移除最早消息() {
        var task = store.create(创建测试消息("msg-0", "ctx-1"));
        // maxHistoryLength = 5, 已有 1 条，再追加 5 条，总 6 条应被裁剪到 5
        IntStream.rangeClosed(1, 5).forEach(i ->
                store.appendHistory(task.id(), 创建带TaskId的消息("msg-" + i, task.id())));

        A2aTask result = store.find(task.id()).orElseThrow();
        assertThat(result.history()).hasSize(5);
        // 最早的 msg-0 应被移除，第一条应为 msg-1
        assertThat(result.history().getFirst().messageId()).isEqualTo("msg-1");
    }

    // ── 取消 ──

    @Test
    void cancel_SUBMITTED状态可取消() {
        var task = store.create(创建测试消息("msg-1", null));
        assertThat(store.cancel(task.id())).isTrue();
        assertThat(store.find(task.id()).orElseThrow().status().state()).isEqualTo(A2aTaskState.CANCELED);
    }

    @Test
    void cancel_WORKING状态可取消() {
        var task = store.create(创建测试消息("msg-1", null));
        store.updateStatus(task.id(), A2aTaskState.WORKING, null);

        assertThat(store.cancel(task.id())).isTrue();
        assertThat(store.find(task.id()).orElseThrow().status().state()).isEqualTo(A2aTaskState.CANCELED);
    }

    @Test
    void cancel_COMPLETED状态不可取消() {
        var task = store.create(创建测试消息("msg-1", null));
        store.updateStatus(task.id(), A2aTaskState.COMPLETED, null);

        assertThat(store.cancel(task.id())).isFalse();
        assertThat(store.find(task.id()).orElseThrow().status().state()).isEqualTo(A2aTaskState.COMPLETED);
    }

    @Test
    void cancel_FAILED状态不可取消() {
        var task = store.create(创建测试消息("msg-1", null));
        store.updateStatus(task.id(), A2aTaskState.FAILED, null);

        assertThat(store.cancel(task.id())).isFalse();
    }

    @Test
    void cancel_不存在的taskId返回false() {
        assertThat(store.cancel("nonexistent")).isFalse();
    }

    // ── TTL 清理 ──

    @Test
    void cleanupExpired_过期Task被清理() {
        var task1 = store.create(创建测试消息("msg-1", null));
        var task2 = store.create(创建测试消息("msg-2", null));

        // 将 createdAt 时间回拨到 2 分钟前（TTL = 1 分钟）
        var pastTime = java.time.Instant.now().minusSeconds(120);
        store.overrideCreatedAt(task1.id(), pastTime);
        store.overrideCreatedAt(task2.id(), pastTime);

        int cleaned = store.cleanupExpired();

        assertThat(cleaned).isEqualTo(2);
        assertThat(store.find(task1.id())).isEmpty();
        assertThat(store.find(task2.id())).isEmpty();
    }

    @Test
    void cleanupExpired_未过期Task不被清理() {
        store.create(创建测试消息("msg-1", null));

        int cleaned = store.cleanupExpired();

        assertThat(cleaned).isZero();
    }
}
