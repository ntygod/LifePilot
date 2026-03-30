package com.lifepilot.interaction.service;

import com.lifepilot.interaction.model.ChannelInstanceEvent;
import com.lifepilot.interaction.repository.ChannelInstanceEventRepository;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 渠道实例事件服务。
 *
 * <p>负责统一封装事件记录与最近事件查询，避免控制面和 runtime 分散拼接仓储逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelInstanceEventService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final ChannelInstanceEventRepository channelInstanceEventRepository;

    public ChannelInstanceEventService(ChannelInstanceEventRepository channelInstanceEventRepository) {
        this.channelInstanceEventRepository = channelInstanceEventRepository;
    }

    public ChannelInstanceEvent record(String instanceId,
                                       String eventType,
                                       @Nullable String message,
                                       @Nullable Map<String, Object> payload) {
        return channelInstanceEventRepository.save(instanceId, eventType, message, payload);
    }

    public List<ChannelInstanceEvent> listRecent(String instanceId, int limit) {
        int safeLimit = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        return channelInstanceEventRepository.findRecentByInstanceId(instanceId, safeLimit);
    }
}
