package com.lifepilot.agent.suspend.store;

import com.lifepilot.agent.suspend.model.SuspendedAgent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 挂起状态持久化接口。
 *
 * <p>定义 Agent 挂起状态的存储、加载、查询、删除和过期清理操作。
 * 实现类需保证 {@link #load(String)} 和 {@link #delete(String)} 在同一事务中执行，
 * 防止并发恢复同一个挂起的 Agent。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public interface SuspendStore {

    /**
     * 保存挂起状态快照。
     *
     * @param agent 挂起状态快照
     */
    void save(SuspendedAgent agent);

    /**
     * 按 traceId 加载挂起状态。
     *
     * @param traceId Agent 轨迹 ID
     * @return 挂起状态快照，不存在时返回 empty
     */
    Optional<SuspendedAgent> load(String traceId);

    /**
     * 按 sessionId 查询所有挂起状态。
     *
     * @param sessionId 会话 ID
     * @return 该会话下的所有挂起状态列表
     */
    List<SuspendedAgent> findBySession(String sessionId);

    /**
     * 按挂起原因类型查询所有挂起状态。
     *
     * @param reasonType SuspendReason 子类型名（如 "WorkflowWait"）
     * @return 该类型下的所有挂起状态列表
     */
    List<SuspendedAgent> findByReasonType(String reasonType);

    /**
     * 按 traceId 删除挂起状态。
     *
     * @param traceId Agent 轨迹 ID
     */
    void delete(String traceId);

    /**
     * 原子加载并删除挂起状态，防止并发恢复同一个 Agent。
     *
     * <p>默认实现分两步执行（非原子），子类应使用事务保证原子性。</p>
     *
     * @param traceId Agent 轨迹 ID
     * @return 挂起状态快照，不存在时返回 empty
     */
    default Optional<SuspendedAgent> loadAndDelete(String traceId) {
        Optional<SuspendedAgent> agent = load(traceId);
        agent.ifPresent(_ -> delete(traceId));
        return agent;
    }

    /**
     * 清理超过 maxAge 的过期挂起记录。
     *
     * @param maxAge 最大保留时长
     * @return 删除的记录数量
     */
    int cleanExpired(Duration maxAge);
}
