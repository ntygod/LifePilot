package com.lifepilot.skill.builtin.todo;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.adapter.CrudAdapterConfig;
import com.lifepilot.datastore.adapter.DataStoreCrudAdapter;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.FilterOp;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.Urgency;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待办到期通知器 — 定时扫描到期待办并发送通知。
 *
 * <p>通过 {@link com.lifepilot.config.threadpool.SharedScheduler} 定时调度 {@link #scan()}，
 * 扫描 24 小时内到期的 PENDING 待办，通过 {@link NotificationService} 发送通知。
 * 使用 {@link ConcurrentHashMap#newKeySet()} 防止重复通知。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class TodoDueNotifier {

    private static final Logger log = LoggerFactory.getLogger(TodoDueNotifier.class);

    private final DataStoreCrudAdapter<TodoEntity> todoAdapter;
    private final NotificationService notificationService;

    /** 已通知的待办标题集合（防重复）。 */
    private final Set<String> notifiedTitles = ConcurrentHashMap.newKeySet();

    public TodoDueNotifier(DataStoreManager dataStoreManager,
                           ObjectMapper objectMapper,
                           NotificationService notificationService) {
        this.todoAdapter = new DataStoreCrudAdapter<>(dataStoreManager, objectMapper,
                new CrudAdapterConfig<>(
                        "todo",
                        "待办事项",
                        CollectionType.DOCUMENT,
                        TodoEntity.class,
                        List.of(
                                new PropertyDefinition("title", PropertyType.TEXT, true, "待办标题"),
                                new PropertyDefinition("status", PropertyType.SELECT, true, "状态: PENDING/IN_PROGRESS/COMPLETED"),
                                new PropertyDefinition("priority", PropertyType.SELECT, false, "优先级: HIGH/MEDIUM/LOW"),
                                new PropertyDefinition("dueDate", PropertyType.DATE, false, "截止日期")
                        ),
                        "待办事项管理"
                ));
        this.notificationService = notificationService;
    }

    /**
     * 扫描到期待办并发送通知。
     *
     * <p>查询所有 PENDING 状态待办，筛选 24 小时内到期且未通知的项，
     * 根据优先级映射 urgency 后发送通知。</p>
     */
    public void scan() {
        try {
            var filters = List.of(new QueryFilter("status", FilterOp.EQ, "PENDING"));
            List<TodoEntity> pendingTodos = todoAdapter.list(filters, null, null, 0, 1000);
            Instant now = Instant.now();
            Instant deadline = now.plus(Duration.ofHours(24));
            int notified = 0;

            for (TodoEntity todo : pendingTodos) {
                if (todo.dueDate() == null) continue;

                try {
                    Instant dueInstant = Instant.parse(todo.dueDate());
                    // 仅处理 24 小时内到期且尚未过期的待办
                    if (dueInstant.isAfter(now) && !dueInstant.isAfter(deadline)) {
                        String deduplicationKey = todo.title() + "|" + todo.dueDate();
                        if (notifiedTitles.add(deduplicationKey)) {
                            Urgency urgency = mapPriorityToUrgency(todo.priority());
                            sendNotification(todo, urgency);
                            notified++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析待办截止日期失败: title={}, dueDate={}", todo.title(), todo.dueDate(), e);
                }
            }

            if (notified > 0) {
                log.info("待办到期通知发送完成: count={}", notified);
            } else {
                log.debug("待办到期扫描完成: 无新到期项");
            }
        } catch (Exception e) {
            log.error("待办到期扫描失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 将待办优先级映射为通知紧急度。
     *
     * @param priority 待办优先级（HIGH/MEDIUM/LOW）
     * @return 通知紧急度
     */
    Urgency mapPriorityToUrgency(String priority) {
        if (priority == null) return Urgency.MEDIUM;
        return switch (priority.toUpperCase()) {
            case "HIGH" -> Urgency.HIGH;
            case "LOW" -> Urgency.LOW;
            default -> Urgency.MEDIUM;
        };
    }

    /** 发送到期通知。 */
    private void sendNotification(TodoEntity todo, Urgency urgency) {
        var content = new ResponseContent.TextContent(
                "待办「%s」将于 %s 到期，请及时处理".formatted(todo.title(), todo.dueDate()));
        var request = new NotificationRequest(
                "default",
                content,
                urgency,
                null,
                "todo_due_reminder",
                Map.of("title", todo.title(), "dueDate", todo.dueDate()));
        notificationService.send(request);
    }

    /** 获取已通知集合大小（测试用）。 */
    int notifiedCount() {
        return notifiedTitles.size();
    }

    /** 清空已通知集合（测试用）。 */
    void clearNotified() {
        notifiedTitles.clear();
    }
}
