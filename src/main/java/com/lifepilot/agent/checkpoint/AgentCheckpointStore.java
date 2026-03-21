package com.lifepilot.agent.checkpoint;

import java.time.Duration;
import java.util.Optional;

/**
 * Agent 检查点存储接口。
 *
 * @author zsg
 * @since 2026-03-21
 */
public interface AgentCheckpointStore {

    void save(AgentCheckpoint checkpoint);

    Optional<AgentCheckpoint> claim(String sessionId, String channel, String taskFingerprint);

    void delete(String sessionId, String channel, String taskFingerprint);

    int cleanExpired(Duration maxAge);
}
