package com.lifepilot.scheduler;

import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.scheduler.model.ScheduledTask;
import com.lifepilot.scheduler.model.TaskAction;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * 定时任务动作执行器。
 *
 * <p>根据 {@link TaskAction} 的子类型执行对应的动作逻辑，
 * 使用 switch 穷举匹配确保所有动作类型都被处理。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class TaskActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskActionExecutor.class);

    private final NotificationService notificationService;
    private final DynamicToolRegistry toolRegistry;
    private final ApplicationContext applicationContext;

    public TaskActionExecutor(NotificationService notificationService,
                              DynamicToolRegistry toolRegistry,
                              ApplicationContext applicationContext) {
        this.notificationService = notificationService;
        this.toolRegistry = toolRegistry;
        this.applicationContext = applicationContext;
    }

    /**
     * 执行定时任务动作。
     *
     * @param task   定时任务实体
     * @param action 待执行的动作
     */
    public void execute(ScheduledTask task, TaskAction action) {
        try {
            switch (action) {
                case TaskAction.SendNotification sn -> handleSendNotification(task, sn);
                case TaskAction.InvokeAgent ia -> handleInvokeAgent(task, ia);
                case TaskAction.ExecuteTool et -> handleExecuteTool(task, et);
            }
        } catch (Exception e) {
            log.error("定时任务动作执行失败: taskId={}, taskName={}, actionType={}, error={}",
                    task.id(), task.name(), action.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /**
     * 处理发送通知动作。
     */
    private void handleSendNotification(ScheduledTask task, TaskAction.SendNotification sn) {
        var request = new NotificationRequest(
                "system",
                new ResponseContent.TextContent(sn.content()),
                sn.urgency(),
                null,
                "scheduled-task",
                Map.of("taskId", task.id(), "taskName", task.name())
        );
        notificationService.send(request);
        log.info("定时任务通知已发送: taskId={}, taskName={}", task.id(), task.name());
    }

    /**
     * 处理调用 Agent 对话动作。
     */
    private void handleInvokeAgent(ScheduledTask task, TaskAction.InvokeAgent ia) {
        var agentLoop = applicationContext.getBean(ReactAgentLoop.class);
        var request = new AgentRequest(
                ia.message(),
                "scheduler:" + task.id(),
                "scheduler",
                null, null, null, 0, null, null, null, null
        );
        agentLoop.run(request);
        log.info("定时任务 Agent 调用完成: taskId={}, taskName={}", task.id(), task.name());
    }

    /**
     * 处理执行工具动作。
     */
    private void handleExecuteTool(ScheduledTask task, TaskAction.ExecuteTool et) {
        ToolContract tool = toolRegistry.resolve(et.toolId())
                .orElseThrow(() -> new IllegalStateException("工具不存在: " + et.toolId()));
        var input = new ToolInput(et.toolId(), et.params(), tool.inputSchema(), null, null);
        ToolResult result = tool.execute(input);
        if (!result.ok()) {
            log.warn("定时任务工具执行返回错误: taskId={}, toolId={}, error={}",
                    task.id(), et.toolId(), result.error());
        } else {
            log.info("定时任务工具执行成功: taskId={}, toolId={}", task.id(), et.toolId());
        }
    }
}
