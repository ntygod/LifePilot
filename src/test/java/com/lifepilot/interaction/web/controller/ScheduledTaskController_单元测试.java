package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskLog;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.scheduled.ScheduledTaskLogResponse;
import com.lifepilot.interaction.web.model.scheduled.ScheduledTaskResponse;
import com.lifepilot.interaction.web.model.scheduled.UpdateScheduledTaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduledTaskController} 单元测试。
 *
 * <p>用 Mockito 隔离 {@link CronTaskRepository}，仅验证 Controller 层参数规范化、
 * DTO 映射与异常转换逻辑。创建入口故意不提供（由 LLM 自然语言触发，Task A6 做），
 * 因此测试只覆盖 list / update / delete / getLogs。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskController_单元测试 {

    @Mock
    CronTaskRepository repository;

    @InjectMocks
    ScheduledTaskController controller;

    /**
     * 构造一个测试用任务。
     *
     * <p>注意 {@link CronTaskEntry} 真实字段顺序：id / name / schedule / instruction /
     * status / createdAt / updatedAt / skillIds / projectId。</p>
     */
    private CronTaskEntry 构造任务(String id, String projectId) {
        String now = Instant.now().toString();
        return new CronTaskEntry(
                id, "任务", "0 0 9 * * *", "指令",
                "active", now, now, null, projectId);
    }

    /** 构造指定状态的任务——专门给 nextExecutionAt 相关用例用。 */
    private CronTaskEntry 构造任务(String id, String projectId, String status) {
        String now = Instant.now().toString();
        return new CronTaskEntry(
                id, "任务", "0 0 9 * * *", "指令",
                status, now, now, null, projectId);
    }

    @Test
    void 列表_不传projectId_返回所有任务() {
        when(repository.findAll()).thenReturn(List.of(
                构造任务("t1", "p-1"),
                构造任务("t2", null)));

        ApiResponse<List<ScheduledTaskResponse>> resp = controller.list(null);

        assertEquals(200, resp.code());
        assertEquals(2, resp.data().size());
    }

    @Test
    void 列表_传projectId_返回该项目的任务() {
        when(repository.findByProjectId("p-1"))
                .thenReturn(List.of(构造任务("t1", "p-1")));

        ApiResponse<List<ScheduledTaskResponse>> resp = controller.list("p-1");

        assertEquals(1, resp.data().size());
        assertEquals("p-1", resp.data().get(0).projectId());
    }

    @Test
    void 列表_传空字符串projectId_视为不过滤() {
        when(repository.findAll()).thenReturn(List.of(构造任务("t1", null)));

        ApiResponse<List<ScheduledTaskResponse>> resp = controller.list("");

        assertEquals(1, resp.data().size());
    }

    @Test
    void 列表_active任务_填充nextExecutionAt() {
        when(repository.findAll()).thenReturn(List.of(构造任务("t1", null, "active")));

        ApiResponse<List<ScheduledTaskResponse>> resp = controller.list(null);

        assertNotNull(resp.data().get(0).nextExecutionAt(),
                "active 任务应计算下次执行时间");
        // 值应为未来时刻（ISO 8601 String），简单比较字符串的字典序是错的，
        // 直接解析成 Instant 对比
        assertTrue(Instant.parse(resp.data().get(0).nextExecutionAt()).isAfter(Instant.now().minusSeconds(1)),
                "下次执行时间应为未来或当下");
    }

    @Test
    void 列表_paused任务_nextExecutionAt为null() {
        when(repository.findAll()).thenReturn(List.of(构造任务("t1", null, "paused")));

        ApiResponse<List<ScheduledTaskResponse>> resp = controller.list(null);

        assertNull(resp.data().get(0).nextExecutionAt(),
                "paused 任务不应计算下次执行时间");
    }

    @Test
    void 更新_代理到repository_且projectId保留不变() {
        CronTaskEntry existing = 构造任务("t1", "p-1");
        when(repository.findById("t1")).thenReturn(Optional.of(existing));

        ApiResponse<ScheduledTaskResponse> resp = controller.update("t1",
                new UpdateScheduledTaskRequest("新名", "0 0 10 * * *", "新指令", "paused"));

        assertNotNull(resp.data());
        assertEquals("新名", resp.data().name());
        assertEquals("paused", resp.data().status());
        assertEquals("p-1", resp.data().projectId());
        verify(repository).update(argThat(updated ->
                "新名".equals(updated.name())
                        && "0 0 10 * * *".equals(updated.schedule())
                        && "新指令".equals(updated.instruction())
                        && "paused".equals(updated.status())
                        && "p-1".equals(updated.projectId())));
    }

    @Test
    void 更新_部分字段为null_保留原值() {
        CronTaskEntry existing = 构造任务("t1", null);
        when(repository.findById("t1")).thenReturn(Optional.of(existing));

        controller.update("t1",
                new UpdateScheduledTaskRequest(null, null, null, "paused"));

        verify(repository).update(argThat(updated ->
                "任务".equals(updated.name())              // 保留原值
                        && "0 0 9 * * *".equals(updated.schedule())   // 保留原值
                        && "指令".equals(updated.instruction())       // 保留原值
                        && "paused".equals(updated.status())));       // 只改 status
    }

    @Test
    void 更新_任务不存在_抛404异常() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.update("nope",
                        new UpdateScheduledTaskRequest("n", "0 * * * * *", "i", "active")));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void 删除_代理到repository_返回空数据的成功响应() {
        ApiResponse<Void> resp = controller.delete("t1");

        verify(repository).deleteById("t1");
        assertEquals(200, resp.code());
        assertNull(resp.data());
    }

    @Test
    void 查询日志_默认limit_5() {
        when(repository.findLogsByTaskId(eq("t1"), eq(5))).thenReturn(List.of(
                new CronTaskLog("log-1", "t1", Instant.now().toString(),
                        "success", 1500, 120, "执行完成", Instant.now().toString())
        ));

        ApiResponse<List<ScheduledTaskLogResponse>> resp = controller.getLogs("t1", 5);

        assertEquals(1, resp.data().size());
        assertEquals("log-1", resp.data().get(0).id());
        assertEquals("success", resp.data().get(0).status());
        assertEquals(1500, resp.data().get(0).durationMs());
    }

    @Test
    void 查询日志_limit超过上限_截断到50() {
        when(repository.findLogsByTaskId(eq("t1"), eq(50))).thenReturn(List.of());

        controller.getLogs("t1", 9999);

        verify(repository).findLogsByTaskId("t1", 50);
    }

    @Test
    void 查询日志_limit小于等于0_提升到1() {
        when(repository.findLogsByTaskId(eq("t1"), eq(1))).thenReturn(List.of());

        controller.getLogs("t1", 0);

        verify(repository).findLogsByTaskId("t1", 1);
    }

    @Test
    void 查询日志_任务不存在_返回空列表不抛异常() {
        when(repository.findLogsByTaskId(eq("nope"), eq(5))).thenReturn(List.of());

        ApiResponse<List<ScheduledTaskLogResponse>> resp = controller.getLogs("nope", 5);

        assertEquals(200, resp.code());
        assertTrue(resp.data().isEmpty());
    }
}
