package com.lifepilot.interaction.channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifepilot.interaction.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 失败消息重试调度器，定期扫描各通道的失败消息队列并重试发送。
 *
 * <p>对 state == RUNNING 的通道重试发送，超过最大重试次数的消息持久化到 {@code failed_messages} 表。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FailedMessageRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(FailedMessageRetryScheduler.class);

    private final List<AbstractChannelAdapter> adapters;
    private final JdbcTemplate jdbcTemplate;
    private final int maxRetryCount;

    public FailedMessageRetryScheduler(List<AbstractChannelAdapter> adapters,
                                       JdbcTemplate jdbcTemplate,
                                       GatewayProperties properties) {
        this.adapters = adapters;
        this.jdbcTemplate = jdbcTemplate;
        this.maxRetryCount = properties.webhook().maxRetryCount();
    }

    /**
     * 定期扫描失败消息队列，对健康通道重试发送。
     */
    @Scheduled(fixedDelayString = "${lifepilot.gateway.webhook.retry-interval-seconds:60}000")
    public void retryFailedMessages() {
        for (var adapter : adapters) {
            if (adapter.getState() != ChannelState.RUNNING) {
                continue;
            }
            processAdapterQueue(adapter);
        }
    }

    private void processAdapterQueue(AbstractChannelAdapter adapter) {
        var queue = adapter.getFailedMessages();
        int processed = 0;

        while (!queue.isEmpty()) {
            var failed = queue.poll();
            if (failed == null) break;

            if (failed.retryCount() >= maxRetryCount) {
                // 超过最大重试次数，持久化到数据库
                persistFailedMessage(adapter, failed);
                continue;
            }

            try {
                adapter.sendResponse(failed.userId(), failed.response());
                processed++;
                log.debug("失败消息重试成功: channel={}, userId={}", adapter.channelType(), failed.userId());
            } catch (Exception e) {
                // 重试失败，重新入队并增加重试计数
                queue.offer(new AbstractChannelAdapter.FailedMessage(
                        failed.userId(), failed.response(),
                        failed.retryCount() + 1, e.getMessage()));
                log.warn("失败消息重试仍然失败: channel={}, userId={}, retryCount={}",
                        adapter.channelType(), failed.userId(), failed.retryCount() + 1);
                break; // 通道可能有问题，停止处理该通道
            }
        }

        if (processed > 0) {
            log.info("失败消息重试完成: channel={}, processed={}", adapter.channelType(), processed);
        }
    }

    private void persistFailedMessage(AbstractChannelAdapter adapter,
                                      AbstractChannelAdapter.FailedMessage failed) {
        try {
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO failed_messages (id, channel, user_id, response_id, content, retry_count, error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    adapter.channelType().value(),
                    failed.userId(),
                    failed.response().responseId(),
                    failed.response().content().toPlainText(),
                    failed.retryCount(),
                    failed.error(),
                    now, now
            );
            log.warn("失败消息已持久化: channel={}, userId={}, retryCount={}",
                    adapter.channelType(), failed.userId(), failed.retryCount());
        } catch (Exception e) {
            log.error("失败消息持久化异常: channel={}, userId={}",
                    adapter.channelType(), failed.userId(), e);
        }
    }
}
