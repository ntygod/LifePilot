package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.scheduled.ScheduledTaskLogResponse;
import com.lifepilot.interaction.web.model.scheduled.ScheduledTaskResponse;
import com.lifepilot.interaction.web.model.scheduled.UpdateScheduledTaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 定时任务（Scheduled Task）REST 端点。
 *
 * <p>提供：列表 / 更新 / 删除 / 执行日志。故意不提供 POST 创建端点——
 * 创建入口保持在 LLM 对话中，由自然语言触发现有的 cron 工具（Task A6 会扩展
 * 工具能力）；本 Controller 只管存量管理（列出、编辑、暂停、删除、查日志）。</p>
 *
 * <p>暂停/恢复通过 PUT 更新 {@code status} 字段实现（active ↔ paused），
 * 避免为每个状态变更单独建端点。</p>
 *
 * <p>Controller 层只做参数规范化与 DTO 映射，持久化委派给
 * {@link CronTaskRepository}。返回 {@link ApiResponse}，错误通过
 * {@link ResponseStatusException} 抛出，由全局异常处理器统一转换。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ScheduledTaskController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskController.class);

    /** 执行日志默认返回条数 */
    private static final int DEFAULT_LOG_LIMIT = 5;
    /** 执行日志最大返回条数——防御性上限 */
    private static final int MAX_LOG_LIMIT = 50;

    private final CronTaskRepository repository;

    public ScheduledTaskController(CronTaskRepository repository) {
        this.repository = repository;
    }

    /**
     * 列出定时任务。
     *
     * <p>{@code projectId} 为空（{@code null} 或空串）时返回所有归属的任务（主账户 +
     * 所有项目）；非空时按项目过滤（精确等值）。归属主账户任务的查询由调用方
     * 传入显式的 {@code "null"} 字符串——本接口不支持此语义，主账户任务
     * 只在"不过滤"列表里返回。</p>
     *
     * <p>响应 DTO 的 {@code nextExecutionAt} 基于 cron 表达式在服务器本地时区
     * 动态计算：仅 active 任务非空。</p>
     */
    @GetMapping
    public ApiResponse<List<ScheduledTaskResponse>> list(
            @RequestParam(required = false) @Nullable String projectId) {
        List<CronTaskEntry> entries = (projectId == null || projectId.isBlank())
                ? repository.findAll()
                : repository.findByProjectId(projectId);
        List<ScheduledTaskResponse> items = entries.stream()
                .map(ScheduledTaskResponse::fromWithNext)
                .toList();
        return ApiResponse.ok(items);
    }

    /**
     * 更新定时任务的 name / schedule / instruction / status。
     *
     * <p>请求体字段为 {@code null} 时保留原值；{@code projectId} 不可更新
     * （归属不可迁移）；{@code createdAt} 不可更新（不可变）；{@code updatedAt}
     * 自动刷新为当前时间。</p>
     *
     * @param id  任务 id
     * @param req 更新请求
     * @return 更新后的任务 DTO（含 nextExecutionAt）
     */
    @PutMapping("/{id}")
    public ApiResponse<ScheduledTaskResponse> update(
            @PathVariable String id,
            @RequestBody UpdateScheduledTaskRequest req) {
        CronTaskEntry existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "定时任务不存在：" + id));
        CronTaskEntry updated = new CronTaskEntry(
                existing.id(),
                req.name() != null ? req.name() : existing.name(),
                req.schedule() != null ? req.schedule() : existing.schedule(),
                req.instruction() != null ? req.instruction() : existing.instruction(),
                req.status() != null ? req.status() : existing.status(),
                existing.createdAt(),
                Instant.now().toString(),
                existing.skillIds(),
                existing.projectId()
        );
        repository.update(updated);
        log.info("更新定时任务: id={}, status={}", id, updated.status());
        return ApiResponse.ok(ScheduledTaskResponse.fromWithNext(updated));
    }

    /**
     * 删除定时任务。
     *
     * <p>级联删除执行日志由 Repository 层的外键约束（ON DELETE CASCADE）保证。
     * 任务不存在不抛异常——幂等删除。</p>
     *
     * @param id 任务 id
     * @return 空数据的成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        repository.deleteById(id);
        log.info("删除定时任务: id={}", id);
        return ApiResponse.ok(null);
    }

    /**
     * 查询指定任务的最近执行日志，按 executed_at 倒序。
     *
     * <p>{@code limit} 缺省 {@value #DEFAULT_LOG_LIMIT}，超过
     * {@value #MAX_LOG_LIMIT} 时截断到上限——避免客户端误传导致大量读取。
     * 任务不存在时不抛 404——返回空列表（等价于"该任务没有执行过"），
     * 前端按空态处理更简单；若任务确实被删掉了，前端拉列表时也看不到。</p>
     *
     * @param id    任务 id
     * @param limit 返回条数上限（默认 5，最大 50）
     */
    @GetMapping("/{id}/logs")
    public ApiResponse<List<ScheduledTaskLogResponse>> getLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "" + DEFAULT_LOG_LIMIT) int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LOG_LIMIT);
        List<ScheduledTaskLogResponse> items = repository.findLogsByTaskId(id, safeLimit).stream()
                .map(ScheduledTaskLogResponse::from)
                .toList();
        return ApiResponse.ok(items);
    }

    /**
     * 按日期查询所有任务的执行日志（跨任务聚合），按 executed_at 倒序。
     *
     * <p>定时任务总览页的"今日叙事 / KPI / 24h ribbon"多处都要用当日日志，单独
     * 为每个任务调 {@link #getLogs} 会产生 N+1 查询；此端点一次性按 date 过滤
     * 返回当日所有日志，前端按 taskId 自行分桶聚合。</p>
     *
     * <p>{@code date} 必须是 ISO 8601 日期（YYYY-MM-DD），非法格式返回 400——
     * 防御性校验，避免 SQLite 的 {@code DATE()} 函数在异常输入下返回空集合时
     * 掩盖客户端 bug。</p>
     *
     * @param date ISO 8601 日期字符串（YYYY-MM-DD）
     */
    @GetMapping("/logs")
    public ApiResponse<List<ScheduledTaskLogResponse>> getLogsByDate(
            @RequestParam String date) {
        // 防御性校验：非法日期直接 400，避免异常参数污染查询
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "date 参数必须是 ISO 8601 日期（YYYY-MM-DD）：" + date);
        }
        List<ScheduledTaskLogResponse> items = repository.findLogsByDate(date).stream()
                .map(ScheduledTaskLogResponse::from)
                .toList();
        return ApiResponse.ok(items);
    }
}
