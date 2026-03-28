package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TaskToolProvider 单元测试。
 *
 * <p>验证 Cron 工具（create/list/update/remove）的行为。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class TaskToolProvider_单元测试 {

    private TaskToolProvider provider;
    private CronTaskRepository cronTaskRepository;
    private CronScheduler cronScheduler;

    @BeforeEach
    void setUp() {
        cronTaskRepository = mock(CronTaskRepository.class);
        cronScheduler = mock(CronScheduler.class);
        provider = new TaskToolProvider(cronTaskRepository, cronScheduler);
    }

    /** 构造测试用 ToolInput。 */
    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("test", params, JsonSchema.empty(), null, null);
    }

    @Test
    void buildCronTools_返回4个工具() {
        List<BuiltinTool> tools = provider.buildCronTools();
        assertThat(tools).hasSize(4);
        assertThat(tools.stream().map(BuiltinTool::id).toList())
                .containsExactly("cron.create", "cron.list",
                        "cron.update", "cron.remove");
    }

    @Test
    void cronCreate_有效表达式_保存并调度() {
        BuiltinTool createTool = findTool(provider.buildCronTools(), "cron.create");

        ToolResult result = createTool.execute(input(Map.of(
                "name", "每日AI资讯",
                "schedule", "0 0 8 * * *",
                "instruction", "搜索最新AI新闻"
        )));

        assertThat(result.isSuccess()).isTrue();
        verify(cronTaskRepository, times(1)).save(any(CronTaskEntry.class));
        verify(cronScheduler, times(1)).schedule(any(CronTaskEntry.class));
    }

    @Test
    void cronCreate_无效表达式_返回错误() {
        BuiltinTool createTool = findTool(provider.buildCronTools(), "cron.create");

        ToolResult result = createTool.execute(input(Map.of(
                "name", "测试",
                "schedule", "invalid-cron",
                "instruction", "测试"
        )));

        assertThat(result.isSuccess()).isFalse();
        verify(cronTaskRepository, never()).save(any());
    }

    @Test
    void cronList_无过滤_返回全部() {
        var entry = new CronTaskEntry("id1", "任务1", "0 0 8 * * *", "指令", "active",
                "2026-03-20T00:00:00Z", "2026-03-20T00:00:00Z");
        when(cronTaskRepository.findAll()).thenReturn(List.of(entry));

        BuiltinTool listTool = findTool(provider.buildCronTools(), "cron.list");
        ToolResult result = listTool.execute(input(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        verify(cronTaskRepository, times(1)).findAll();
    }

    @Test
    void cronUpdate_状态变更为paused_取消调度() {
        var existing = new CronTaskEntry("id1", "任务1", "0 0 8 * * *", "指令", "active",
                "2026-03-20T00:00:00Z", "2026-03-20T00:00:00Z");
        when(cronTaskRepository.findById("id1")).thenReturn(Optional.of(existing));

        BuiltinTool updateTool = findTool(provider.buildCronTools(), "cron.update");
        ToolResult result = updateTool.execute(input(Map.of("taskId", "id1", "status", "paused")));

        assertThat(result.isSuccess()).isTrue();
        verify(cronScheduler, times(1)).cancel("id1");
        verify(cronScheduler, never()).schedule(any());
        verify(cronTaskRepository, times(1)).update(any());
    }

    @Test
    void cronUpdate_状态恢复为active_重新调度() {
        var existing = new CronTaskEntry("id1", "任务1", "0 0 8 * * *", "指令", "paused",
                "2026-03-20T00:00:00Z", "2026-03-20T00:00:00Z");
        when(cronTaskRepository.findById("id1")).thenReturn(Optional.of(existing));

        BuiltinTool updateTool = findTool(provider.buildCronTools(), "cron.update");
        ToolResult result = updateTool.execute(input(Map.of("taskId", "id1", "status", "active")));

        assertThat(result.isSuccess()).isTrue();
        verify(cronScheduler, times(1)).cancel("id1");
        verify(cronScheduler, times(1)).schedule(any());
    }

    @Test
    void cronUpdate_任务不存在_返回错误() {
        when(cronTaskRepository.findById("nonexistent")).thenReturn(Optional.empty());

        BuiltinTool updateTool = findTool(provider.buildCronTools(), "cron.update");
        ToolResult result = updateTool.execute(input(Map.of("taskId", "nonexistent", "status", "paused")));

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void cronRemove_先取消再删除() {
        BuiltinTool removeTool = findTool(provider.buildCronTools(), "cron.remove");
        ToolResult result = removeTool.execute(input(Map.of("taskId", "id1")));

        assertThat(result.isSuccess()).isTrue();
        var inOrder = inOrder(cronScheduler, cronTaskRepository);
        inOrder.verify(cronScheduler).cancel("id1");
        inOrder.verify(cronTaskRepository).deleteById("id1");
    }

    private BuiltinTool findTool(List<BuiltinTool> tools, String id) {
        return tools.stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具不存在: " + id));
    }
}
