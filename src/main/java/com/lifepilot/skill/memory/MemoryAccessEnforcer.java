package com.lifepilot.skill.memory;

import com.lifepilot.skill.model.MemoryAccessPolicy;
import com.lifepilot.skill.model.MemoryReadPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆访问强制执行器 — 运行时拦截未授权的记忆访问。
 *
 * <p>在 SubAgent 的 ContextAssembler 中注入，作为装饰器包装记忆检索接口，
 * 在实际访问前检查权限，违规时立即抛出 {@link MemoryAccessViolationException}。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class MemoryAccessEnforcer {

    private static final Logger log = LoggerFactory.getLogger(MemoryAccessEnforcer.class);

    /**
     * 时间范围格式：数字 + 单位（d=天, h=小时, m=分钟）。
     * 例如 "30d" 表示 30 天，"24h" 表示 24 小时。
     */
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("^(\\d+)([dhm])$");

    /**
     * 检查读权限，违规时抛出 MemoryAccessViolationException。
     *
     * @param policy     记忆访问策略
     * @param layer      记忆层标识
     * @param entityType 实体类型
     * @throws MemoryAccessViolationException 当策略不允许读取时
     */
    public void checkRead(MemoryAccessPolicy policy, String layer, String entityType) {
        if (!policy.canRead(layer, entityType)) {
            log.warn("未授权的记忆读取: layer={}, entityType={}", layer, entityType);
            throw new MemoryAccessViolationException(
                    "未授权的记忆读取: layer=%s, entityType=%s".formatted(layer, entityType));
        }
    }

    /**
     * 检查写权限，违规时抛出 MemoryAccessViolationException。
     *
     * @param policy     记忆访问策略
     * @param layer      记忆层标识
     * @param entityType 实体类型
     * @throws MemoryAccessViolationException 当策略不允许写入时
     */
    public void checkWrite(MemoryAccessPolicy policy, String layer, String entityType) {
        if (!policy.canWrite(layer, entityType)) {
            log.warn("未授权的记忆写入: layer={}, entityType={}", layer, entityType);
            throw new MemoryAccessViolationException(
                    "未授权的记忆写入: layer=%s, entityType=%s".formatted(layer, entityType));
        }
    }

    /**
     * 检查写操作是否需要用户确认。
     *
     * <p>遍历策略中的写权限列表，查找匹配 (layer, entityType) 的条目，
     * 如果该条目的 requireApproval 为 true，则返回 true。</p>
     *
     * @param policy     记忆访问策略
     * @param layer      记忆层标识
     * @param entityType 实体类型
     * @return 是否需要用户确认
     */
    public boolean requiresApproval(MemoryAccessPolicy policy, String layer, String entityType) {
        return policy.write().stream()
                .anyMatch(p -> p.layer().equals(layer)
                        && (p.entityTypes().contains("*") || p.entityTypes().contains(entityType))
                        && p.requireApproval());
    }

    /**
     * 检查时间范围约束。
     *
     * <p>解析 MemoryReadPermission 中的 timeRange（格式如 "30d" 表示 30 天），
     * 检查 queryTime 是否在 now - timeRange 到 now 的范围内。
     * 超出范围时抛出 MemoryAccessViolationException。</p>
     *
     * @param policy    记忆访问策略
     * @param layer     记忆层标识
     * @param queryTime 查询时间点
     * @throws MemoryAccessViolationException 当查询时间超出声明的时间范围时
     */
    public void checkTimeRange(MemoryAccessPolicy policy, String layer, Instant queryTime) {
        for (MemoryReadPermission permission : policy.read()) {
            if (!permission.layer().equals(layer)) {
                continue;
            }
            String timeRange = permission.timeRange();
            if (timeRange == null || timeRange.isBlank()) {
                // 无时间范围约束，允许访问
                return;
            }
            Duration duration = parseTimeRange(timeRange);
            Instant earliest = Instant.now().minus(duration);
            if (queryTime.isBefore(earliest)) {
                log.warn("记忆查询时间超出范围: layer={}, timeRange={}, queryTime={}", layer, timeRange, queryTime);
                throw new MemoryAccessViolationException(
                        "记忆查询时间超出范围: layer=%s, timeRange=%s, queryTime=%s"
                                .formatted(layer, timeRange, queryTime));
            }
            // 找到匹配的权限且时间范围内，直接返回
            return;
        }
    }

    /**
     * 解析时间范围字符串为 Duration。
     *
     * @param timeRange 时间范围字符串（如 "30d"、"24h"、"60m"）
     * @return 对应的 Duration
     * @throws MemoryAccessViolationException 当格式不合法时
     */
    private Duration parseTimeRange(String timeRange) {
        Matcher matcher = TIME_RANGE_PATTERN.matcher(timeRange);
        if (!matcher.matches()) {
            throw new MemoryAccessViolationException("无效的时间范围格式: " + timeRange);
        }
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        return switch (unit) {
            case "d" -> Duration.ofDays(value);
            case "h" -> Duration.ofHours(value);
            case "m" -> Duration.ofMinutes(value);
            default -> throw new MemoryAccessViolationException("不支持的时间单位: " + unit);
        };
    }
}
