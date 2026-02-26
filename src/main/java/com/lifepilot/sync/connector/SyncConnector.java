package com.lifepilot.sync.connector;

import com.lifepilot.sync.model.*;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 同步连接器接口 — 封装与单个外部服务的通信协议。
 *
 * <p>每个连接器实现负责与特定外部数据源（CalDAV / Todoist / 滴答清单 / Obsidian）
 * 的通信，包括认证、数据拉取、数据推送。</p>
 *
 * <p>实现类限定为：
 * {@link com.lifepilot.sync.connector.caldav.CalDavConnector}、
 * {@link com.lifepilot.sync.connector.todoist.TodoistConnector}、
 * {@link com.lifepilot.sync.connector.dida.DidaConnector}、
 * {@link com.lifepilot.sync.connector.obsidian.ObsidianConnector}。
 * 由于 Java 未命名模块限制，sealed interface 无法跨子包 permits，
 * 因此使用普通接口 + 文档约束替代。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public interface SyncConnector {

    /**
     * 连接器类型标识（如 "caldav"、"todoist"、"dida"、"obsidian"）。
     *
     * @return 类型标识字符串
     */
    String type();

    /**
     * 测试连接是否可用。
     *
     * @param profile 同步配置
     * @return 连接测试结果
     */
    ConnectionTestResult testConnection(SyncProfile profile);

    /**
     * 拉取远程变更。
     *
     * <p>当 syncToken 为 null 时执行全量同步，返回所有远程实体作为新增项。</p>
     *
     * @param profile   同步配置
     * @param syncToken 增量同步令牌，首次同步时为 null
     * @return 远程变更集
     */
    RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken);

    /**
     * 推送本地变更到远程。
     *
     * @param profile    同步配置
     * @param operations 同步操作列表
     * @return 推送结果
     */
    PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations);
}
