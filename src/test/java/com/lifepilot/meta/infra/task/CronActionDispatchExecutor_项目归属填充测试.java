package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CronActionDispatchExecutor 项目归属填充测试（Plan 2 Task A6）。
 *
 * <p>验证在对话中通过 cron 工具创建定时任务时，执行器会反查
 * 当前 session 的 projectId 并填入新建的 {@link CronTaskEntry}：</p>
 * <ul>
 *   <li>隔离项目对话：projectId 写入任务归属</li>
 *   <li>主账户对话：projectId 为 null</li>
 *   <li>ChatSessionRepository 缺失：回退 null，不抛异常</li>
 *   <li>session 不存在：回退 null</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
class CronActionDispatchExecutor_项目归属填充测试 {

    /** 通用的 create action 参数。 */
    private static Map<String, Object> createParams() {
        return Map.of(
                "action", "create",
                "name", "每日 AI 资讯",
                "schedule", "0 0 8 * * *",
                "instruction", "搜索最新 AI 新闻"
        );
    }

    @Test
    void 创建定时任务_隔离项目对话_填充projectId() {
        var cronTaskRepository = mock(CronTaskRepository.class);
        var cronScheduler = mock(CronScheduler.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);

        when(chatSessionRepository.findById("s-1"))
                .thenReturn(Optional.of(session("s-1", "p-1")));

        var provider = new TaskToolProvider(cronTaskRepository, cronScheduler, chatSessionRepository);
        BuiltinTool cronTool = findCronTool(provider);

        ToolResult result = cronTool.execute(new ToolInput(
                cronTool.id(), createParams(), cronTool.inputSchema(), null,
                Map.of("sessionId", "s-1")));

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<CronTaskEntry> captor = ArgumentCaptor.forClass(CronTaskEntry.class);
        verify(cronTaskRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo("p-1");
    }

    @Test
    void 创建定时任务_主账户对话_projectId为null() {
        var cronTaskRepository = mock(CronTaskRepository.class);
        var cronScheduler = mock(CronScheduler.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);

        when(chatSessionRepository.findById("s-main"))
                .thenReturn(Optional.of(session("s-main", null)));

        var provider = new TaskToolProvider(cronTaskRepository, cronScheduler, chatSessionRepository);
        BuiltinTool cronTool = findCronTool(provider);

        ToolResult result = cronTool.execute(new ToolInput(
                cronTool.id(), createParams(), cronTool.inputSchema(), null,
                Map.of("sessionId", "s-main")));

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<CronTaskEntry> captor = ArgumentCaptor.forClass(CronTaskEntry.class);
        verify(cronTaskRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }

    @Test
    void 创建定时任务_chatSessionRepository缺失_回退projectId为null() {
        var cronTaskRepository = mock(CronTaskRepository.class);
        var cronScheduler = mock(CronScheduler.class);

        // 未注入 ChatSessionRepository —— 等价于独立部署、无 interaction 模块的场景
        var provider = new TaskToolProvider(cronTaskRepository, cronScheduler, null);
        BuiltinTool cronTool = findCronTool(provider);

        ToolResult result = cronTool.execute(new ToolInput(
                cronTool.id(), createParams(), cronTool.inputSchema(), null,
                Map.of("sessionId", "s-any")));

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<CronTaskEntry> captor = ArgumentCaptor.forClass(CronTaskEntry.class);
        verify(cronTaskRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }

    @Test
    void 创建定时任务_session不存在_回退projectId为null() {
        var cronTaskRepository = mock(CronTaskRepository.class);
        var cronScheduler = mock(CronScheduler.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);

        when(chatSessionRepository.findById(any())).thenReturn(Optional.empty());

        var provider = new TaskToolProvider(cronTaskRepository, cronScheduler, chatSessionRepository);
        BuiltinTool cronTool = findCronTool(provider);

        ToolResult result = cronTool.execute(new ToolInput(
                cronTool.id(), createParams(), cronTool.inputSchema(), null,
                Map.of("sessionId", "s-ghost")));

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<CronTaskEntry> captor = ArgumentCaptor.forClass(CronTaskEntry.class);
        verify(cronTaskRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }

    @Test
    void 创建定时任务_无sessionId上下文_回退projectId为null() {
        var cronTaskRepository = mock(CronTaskRepository.class);
        var cronScheduler = mock(CronScheduler.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);

        var provider = new TaskToolProvider(cronTaskRepository, cronScheduler, chatSessionRepository);
        BuiltinTool cronTool = findCronTool(provider);

        // context 为 null —— 等价于非对话触发（例如 CLI 或 HTTP 直接调用）
        ToolResult result = cronTool.execute(new ToolInput(
                cronTool.id(), createParams(), cronTool.inputSchema(), null, null));

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<CronTaskEntry> captor = ArgumentCaptor.forClass(CronTaskEntry.class);
        verify(cronTaskRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }

    private BuiltinTool findCronTool(TaskToolProvider provider) {
        List<BuiltinTool> tools = provider.buildCronTools();
        return tools.stream()
                .filter(t -> t.id().equals("cron"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("cron 工具未注册"));
    }
}
