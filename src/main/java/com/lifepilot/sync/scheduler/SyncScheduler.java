package com.lifepilot.sync.scheduler;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.engine.SyncEngine;
import com.lifepilot.sync.model.SyncProfile;
import com.lifepilot.sync.model.SyncResult;
import com.lifepilot.sync.repository.SyncProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同步调度器 — 管理定时轮询和事件触发的同步任务。
 *
 * <p>使用 {@link ScheduledExecutorService} + Virtual Thread 执行同步任务，
 * 支持基于 Cron 表达式的定时调度和事件触发同步。</p>
 *
 * <p>核心特性：
 * <ul>
 *   <li>定时调度：基于 SyncProfile.cronExpression 定时执行同步</li>
 *   <li>事件触发：监听本地数据变更事件，触发相关 profile 的同步</li>
 *   <li>防抖：同一 profile 事件触发间隔不小于 event-sync-min-interval</li>
 *   <li>重叠检测：同一 profile 已有同步运行时跳过新请求</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    /** 匹配 "0 *\/N * * * *" 格式的 Cron 表达式，提取分钟间隔 N。 */
    private static final Pattern CRON_EVERY_N_MINUTES = Pattern.compile(
            "^\\d+\\s+\\*/?(\\d+)\\s+\\*\\s+\\*\\s+\\*\\s+\\*$");

    /** 匹配 "0 0 *\/N * * *" 格式的 Cron 表达式，提取小时间隔 N。 */
    private static final Pattern CRON_EVERY_N_HOURS = Pattern.compile(
            "^\\d+\\s+\\d+\\s+\\*/?(\\d+)\\s+\\*\\s+\\*\\s+\\*$");

    /** 默认调度间隔（分钟），当 Cron 表达式无法解析时使用。 */
    private static final long DEFAULT_INTERVAL_MINUTES = 15;

    private final SyncEngine syncEngine;
    private final SyncProfileRepository profileRepository;
    private final SyncProperties properties;
    private final ScheduledExecutorService scheduler;

    /** 防抖：记录每个 profile 上次事件触发同步的时间。 */
    private final ConcurrentHashMap<String, Instant> lastEventSyncTime = new ConcurrentHashMap<>();

    /** 重叠检测：标记正在运行同步的 profile。 */
    private final ConcurrentHashMap<String, Boolean> runningProfiles = new ConcurrentHashMap<>();

    /** 跟踪已调度的定时任务，用于取消和刷新。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public SyncScheduler(SyncEngine syncEngine,
                         SyncProfileRepository profileRepository,
                         SyncProperties properties) {
        this.syncEngine = syncEngine;
        this.profileRepository = profileRepository;
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    /**
     * 可见性测试构造函数 — 允许注入自定义 ScheduledExecutorService。
     *
     * @param syncEngine        同步引擎
     * @param profileRepository 同步配置仓储
     * @param properties        同步配置属性
     * @param scheduler         自定义调度器
     */
    SyncScheduler(SyncEngine syncEngine,
                  SyncProfileRepository profileRepository,
                  SyncProperties properties,
                  ScheduledExecutorService scheduler) {
        this.syncEngine = syncEngine;
        this.profileRepository = profileRepository;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 启动定时同步任务 — 加载所有已启用的 SyncProfile 并调度。
     *
     * <p>仅调度 enabled=true 的 profile，disabled 的 profile 不会被调度。</p>
     */
    public void start() {
        List<SyncProfile> enabledProfiles = profileRepository.findAllEnabled();
        log.info("同步调度器启动: 发现 {} 个已启用的同步配置", enabledProfiles.size());

        for (SyncProfile profile : enabledProfiles) {
            scheduleProfile(profile);
        }
    }

    /**
     * 事件触发同步 — 根据数据类型找到匹配的 profile 并触发同步。
     *
     * <p>带防抖和重叠检测：
     * <ul>
     *   <li>同一 profile 的事件触发间隔小于 event-sync-min-interval 时跳过</li>
     *   <li>同一 profile 已有同步运行时跳过</li>
     * </ul>
     *
     * @param dataType 变更的数据类型（如 "TodoItem"、"ScheduleItem"、"HabitItem"）
     */
    public void triggerEventSync(String dataType) {
        List<SyncProfile> enabledProfiles = profileRepository.findAllEnabled();

        for (SyncProfile profile : enabledProfiles) {
            // 检查 profile 是否包含该数据类型
            if (!profileMatchesDataType(profile, dataType)) {
                continue;
            }

            // 防抖检查
            if (isWithinDebounceInterval(profile.id())) {
                log.debug("事件触发同步被防抖跳过: profileId={}, dataType={}", profile.id(), dataType);
                continue;
            }

            // 重叠检测
            if (isProfileRunning(profile.id())) {
                log.warn("事件触发同步被重叠跳过: profileId={}, dataType={}", profile.id(), dataType);
                continue;
            }

            log.info("事件触发同步: profileId={}, dataType={}", profile.id(), dataType);
            // 异步执行同步
            scheduler.execute(() -> executeSyncSafely(profile));
        }
    }

    /**
     * 刷新指定 profile 的调度配置。
     *
     * <p>取消已有的定时任务，如果 profile 仍然启用则重新调度。</p>
     *
     * @param profileId 同步配置 ID
     */
    public void refreshSchedule(String profileId) {
        // 取消已有调度
        ScheduledFuture<?> existing = scheduledTasks.remove(profileId);
        if (existing != null) {
            existing.cancel(false);
            log.info("已取消 profile 的定时调度: profileId={}", profileId);
        }

        // 重新调度（如果仍然启用）
        profileRepository.findById(profileId).ifPresent(profile -> {
            if (profile.enabled()) {
                scheduleProfile(profile);
                log.info("已刷新 profile 的定时调度: profileId={}", profileId);
            } else {
                log.info("Profile 已禁用，不重新调度: profileId={}", profileId);
            }
        });
    }

    /**
     * 关闭调度器，释放资源。
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("同步调度器已关闭");
    }

    // ---- 内部方法 ----

    /**
     * 为单个 profile 创建定时调度任务。
     */
    private void scheduleProfile(SyncProfile profile) {
        long intervalMinutes = parseCronToIntervalMinutes(profile.cronExpression());
        long intervalSeconds = intervalMinutes * 60;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeSyncSafely(profile),
                intervalSeconds,  // 初始延迟 = 间隔时间（避免启动时立即执行）
                intervalSeconds,
                TimeUnit.SECONDS);

        scheduledTasks.put(profile.id(), future);
        log.info("已调度同步任务: profileId={}, name={}, 间隔={}分钟",
                profile.id(), profile.name(), intervalMinutes);
    }

    /**
     * 安全执行同步 — 包含重叠检测和异常捕获。
     */
    private void executeSyncSafely(SyncProfile profile) {
        // 重叠检测：putIfAbsent 返回 null 表示成功获取锁
        if (runningProfiles.putIfAbsent(profile.id(), Boolean.TRUE) != null) {
            log.warn("同步任务重叠，跳过执行: profileId={}", profile.id());
            return;
        }

        try {
            log.info("开始执行同步: profileId={}, name={}", profile.id(), profile.name());
            SyncResult result = syncEngine.sync(profile);
            log.info("同步完成: profileId={}, 状态={}, 拉取={}, 推送={}, 冲突={}",
                    profile.id(), result.status(), result.pulledCount(),
                    result.pushedCount(), result.conflictsDetected());

            // 更新事件触发时间（用于防抖）
            lastEventSyncTime.put(profile.id(), Instant.now());
        } catch (Exception e) {
            log.error("同步任务异常: profileId={}, 错误={}", profile.id(), e.getMessage(), e);
        } finally {
            runningProfiles.remove(profile.id());
        }
    }

    /**
     * 检查 profile 是否在防抖间隔内。
     *
     * @param profileId 同步配置 ID
     * @return true 表示在防抖间隔内，应跳过
     */
    boolean isWithinDebounceInterval(String profileId) {
        Instant lastSync = lastEventSyncTime.get(profileId);
        if (lastSync == null) {
            return false;
        }
        Duration elapsed = Duration.between(lastSync, Instant.now());
        return elapsed.getSeconds() < properties.getEventSyncMinInterval();
    }

    /**
     * 检查 profile 是否正在运行同步。
     *
     * @param profileId 同步配置 ID
     * @return true 表示正在运行
     */
    boolean isProfileRunning(String profileId) {
        return runningProfiles.containsKey(profileId);
    }

    /**
     * 检查 profile 的 dataTypeFilter 是否包含指定的数据类型。
     *
     * @param profile  同步配置
     * @param dataType 数据类型
     * @return true 表示匹配
     */
    boolean profileMatchesDataType(SyncProfile profile, String dataType) {
        String filter = profile.dataTypeFilterJson();
        if (filter == null || filter.isBlank()) {
            // 无过滤器 → 匹配所有类型
            return true;
        }

        // 简单 JSON 数组解析
        String content = filter.trim();
        if (content.startsWith("[")) {
            content = content.substring(1);
        }
        if (content.endsWith("]")) {
            content = content.substring(0, content.length() - 1);
        }

        if (content.isBlank()) {
            return true;
        }

        Set<String> types = new HashSet<>();
        for (String part : content.split(",")) {
            String type = part.trim().replace("\"", "");
            if (!type.isBlank()) {
                types.add(type);
            }
        }

        return types.isEmpty() || types.contains(dataType);
    }

    /**
     * 解析 Cron 表达式为调度间隔（分钟）。
     *
     * <p>支持基本模式：
     * <ul>
     *   <li>{@code "0 *&#47;N * * * *"} → 每 N 分钟</li>
     *   <li>{@code "0 0 *&#47;N * * *"} → 每 N 小时</li>
     * </ul>
     * 无法解析时回退到默认 15 分钟。</p>
     *
     * @param cronExpression Cron 表达式
     * @return 间隔分钟数
     */
    long parseCronToIntervalMinutes(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return DEFAULT_INTERVAL_MINUTES;
        }

        String cron = cronExpression.trim();

        // 尝试匹配 "0 */N * * * *"（每 N 分钟）
        Matcher minuteMatcher = CRON_EVERY_N_MINUTES.matcher(cron);
        if (minuteMatcher.matches()) {
            try {
                long minutes = Long.parseLong(minuteMatcher.group(1));
                return minutes > 0 ? minutes : DEFAULT_INTERVAL_MINUTES;
            } catch (NumberFormatException e) {
                // 回退默认值
            }
        }

        // 尝试匹配 "0 0 */N * * *"（每 N 小时）
        Matcher hourMatcher = CRON_EVERY_N_HOURS.matcher(cron);
        if (hourMatcher.matches()) {
            try {
                long hours = Long.parseLong(hourMatcher.group(1));
                return hours > 0 ? hours * 60 : DEFAULT_INTERVAL_MINUTES;
            } catch (NumberFormatException e) {
                // 回退默认值
            }
        }

        log.warn("无法解析 Cron 表达式，使用默认间隔: cron={}, 默认={}分钟",
                cronExpression, DEFAULT_INTERVAL_MINUTES);
        return DEFAULT_INTERVAL_MINUTES;
    }

    // ---- 测试辅助方法 ----

    /** 获取已调度任务数量（仅测试用）。 */
    int getScheduledTaskCount() {
        return scheduledTasks.size();
    }

    /** 获取已调度任务的 profile ID 集合（仅测试用）。 */
    Set<String> getScheduledProfileIds() {
        return Set.copyOf(scheduledTasks.keySet());
    }

    /** 手动设置上次事件同步时间（仅测试用）。 */
    void setLastEventSyncTime(String profileId, Instant time) {
        lastEventSyncTime.put(profileId, time);
    }

    /** 手动标记 profile 为运行中（仅测试用）。 */
    void markProfileRunning(String profileId) {
        runningProfiles.put(profileId, Boolean.TRUE);
    }

    /** 手动清除 profile 运行标记（仅测试用）。 */
    void clearProfileRunning(String profileId) {
        runningProfiles.remove(profileId);
    }
}
