package com.lifepilot.a2a.client;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.A2aAgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 远程 A2A Agent 注册表。
 *
 * <p>管理已配置的远程 Agent URL 列表，缓存 Agent Card，
 * 支持 TTL 过期重新获取。应用启动时自动发现配置的远程 Agent。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class RemoteAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentRegistry.class);

    /** 远程 Agent 缓存条目。 */
    private record CacheEntry(A2aAgentCard card, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();
    private final A2aClientService clientService;
    private final A2aProperties properties;

    public RemoteAgentRegistry(A2aClientService clientService, A2aProperties properties) {
        this.clientService = clientService;
        this.properties = properties;
    }

    /**
     * 注册远程 Agent（自动获取并缓存 Agent Card）。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @return 是否注册成功
     */
    public boolean register(String agentUrl) {
        return clientService.discoverAgent(agentUrl)
                .map(card -> {
                    cache.put(agentUrl, new CacheEntry(card, Instant.now()));
                    log.info("远程 Agent 注册成功: url={}, name={}", agentUrl, card.name());
                    return true;
                })
                .orElseGet(() -> {
                    log.warn("远程 Agent 注册失败，无法获取 Agent Card: url={}", agentUrl);
                    return false;
                });
    }

    /**
     * 注销远程 Agent。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @return 是否注销成功（URL 存在则为 true）
     */
    public boolean unregister(String agentUrl) {
        CacheEntry removed = cache.remove(agentUrl);
        refreshLocks.remove(agentUrl);
        if (removed != null) {
            log.info("远程 Agent 注销成功: url={}", agentUrl);
            return true;
        }
        return false;
    }

    /**
     * 列出所有已注册远程 Agent 的 Agent Card。
     *
     * @return Agent Card 列表（不可变）
     */
    public List<A2aAgentCard> listAll() {
        return List.copyOf(cache.values().stream()
                .map(CacheEntry::card)
                .toList());
    }

    /**
     * 按 URL 查找远程 Agent（检查 TTL，过期则重新获取）。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @return Agent Card（不存在或过期获取失败返回空 Optional）
     */
    public Optional<A2aAgentCard> findByUrl(String agentUrl) {
        return getOrRefresh(agentUrl);
    }

    /**
     * 启动时自动发现配置的远程 Agent。
     *
     * <p>从 A2aProperties.client.remoteAgents 列表逐个发现，
     * 失败跳过并记录 WARN 日志，不阻塞启动。</p>
     */
    public void discoverConfiguredAgents() {
        List<String> remoteAgents = properties.getClient().getRemoteAgents();
        if (remoteAgents == null || remoteAgents.isEmpty()) {
            log.debug("未配置远程 Agent，跳过自动发现");
            return;
        }
        log.info("开始自动发现远程 Agent: 共 {} 个", remoteAgents.size());
        for (String agentUrl : remoteAgents) {
            if (!register(agentUrl)) {
                log.warn("自动发现远程 Agent 失败，跳过: url={}", agentUrl);
            }
        }
        log.info("远程 Agent 自动发现完成: 成功 {} 个", cache.size());
    }

    /**
     * 检查缓存是否过期，过期则重新获取。
     *
     * <p>使用 per-URL ReentrantLock 防止多线程同时刷新同一 URL（thundering herd）。</p>
     */
    private Optional<A2aAgentCard> getOrRefresh(String agentUrl) {
        CacheEntry entry = cache.get(agentUrl);
        if (entry == null) {
            return Optional.empty();
        }

        int ttlMinutes = properties.getClient().getCardCacheTtlMinutes();
        if (entry.fetchedAt().plusSeconds(ttlMinutes * 60L).isBefore(Instant.now())) {
            // 缓存过期，使用锁保证同一 URL 同时只有一个线程刷新
            var lock = refreshLocks.computeIfAbsent(agentUrl, _ -> new ReentrantLock());
            if (lock.tryLock()) {
                try {
                    // 双重检查：获取锁后重新检查缓存是否已被其他线程刷新
                    CacheEntry recheck = cache.get(agentUrl);
                    if (recheck != null && recheck.fetchedAt().plusSeconds(ttlMinutes * 60L).isAfter(Instant.now())) {
                        return Optional.of(recheck.card());
                    }
                    log.debug("远程 Agent Card 缓存过期，重新获取: url={}", agentUrl);
                    return clientService.discoverAgent(agentUrl)
                            .map(card -> {
                                cache.put(agentUrl, new CacheEntry(card, Instant.now()));
                                return card;
                            })
                            .or(() -> {
                                log.warn("远程 Agent Card 刷新失败，使用旧缓存: url={}", agentUrl);
                                return Optional.of(entry.card());
                            });
                } finally {
                    lock.unlock();
                }
            } else {
                // 其他线程正在刷新，直接返回旧缓存
                return Optional.of(entry.card());
            }
        }

        return Optional.of(entry.card());
    }
}
