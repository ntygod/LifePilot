package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.project.CreateProjectRequest;
import com.lifepilot.interaction.web.model.project.ProjectResponse;
import com.lifepilot.interaction.web.model.project.UpdateProjectRequest;
import com.lifepilot.project.exception.ProjectNotFoundException;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectController} 单元测试。
 *
 * <p>用 Mockito 隔离 {@link ProjectService}，仅验证 Controller 层参数规范化、
 * DTO 映射与异常转换逻辑。真实 HTTP 往返与 MemorySpace 联动留给集成测试。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class ProjectController_单元测试 {

    @Mock
    ProjectService service;

    @InjectMocks
    ProjectController controller;

    private Project 构造项目(String id, String name) {
        return new Project(
                id,
                name,
                "",
                ProjectIsolation.ISOLATED,
                "ms-" + id,
                Instant.parse("2026-04-23T00:00:00Z"),
                Instant.parse("2026-04-23T00:00:00Z")
        );
    }

    @Test
    void 创建项目_传入完整参数_委派给service并返回DTO() {
        Project created = 构造项目("p-1", "论文");
        when(service.createProject(eq("论文"), eq("严谨"), eq(ProjectIsolation.ISOLATED)))
                .thenReturn(created);

        ApiResponse<ProjectResponse> resp = controller.create(
                new CreateProjectRequest("论文", "严谨", "ISOLATED"));

        assertEquals(200, resp.code());
        assertNotNull(resp.data());
        assertEquals("p-1", resp.data().id());
        assertEquals("论文", resp.data().name());
        assertEquals("ISOLATED", resp.data().isolation());
        assertEquals("ms-p-1", resp.data().memorySpaceId());
    }

    @Test
    void 创建项目_instructions为null时归一化为空串_isolation为null时走默认ISOLATED() {
        Project created = 构造项目("p-1", "项目");
        when(service.createProject(anyString(), anyString(), eq(ProjectIsolation.ISOLATED)))
                .thenReturn(created);

        controller.create(new CreateProjectRequest("项目", null, null));

        verify(service).createProject("项目", "", ProjectIsolation.ISOLATED);
    }

    @Test
    void 列表接口_返回service查出的所有项目() {
        when(service.listProjects()).thenReturn(List.of(
                构造项目("p-1", "论文"),
                构造项目("p-2", "读书")
        ));

        ApiResponse<List<ProjectResponse>> resp = controller.list();

        assertEquals(2, resp.data().size());
        assertEquals("p-1", resp.data().get(0).id());
        assertEquals("读书", resp.data().get(1).name());
    }

    @Test
    void 按id查询_项目存在时返回对应DTO() {
        when(service.getProject("p-1"))
                .thenReturn(Optional.of(构造项目("p-1", "论文")));

        ApiResponse<ProjectResponse> resp = controller.get("p-1");

        assertEquals("p-1", resp.data().id());
        assertEquals("论文", resp.data().name());
    }

    @Test
    void 按id查询_项目不存在时抛404异常() {
        when(service.getProject("nope")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.get("nope"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void 更新项目_将DTO字段透传给service() {
        Project updated = 构造项目("p-1", "新名");
        when(service.updateProject(eq("p-1"), eq("新名"), eq("更新"), eq(ProjectIsolation.SHARED)))
                .thenReturn(updated);

        ApiResponse<ProjectResponse> resp = controller.update("p-1",
                new UpdateProjectRequest("新名", "更新", "SHARED"));

        assertEquals("新名", resp.data().name());
        verify(service).updateProject("p-1", "新名", "更新", ProjectIsolation.SHARED);
    }

    @Test
    void 更新项目_isolation为null时传递null表示保留原值() {
        Project updated = 构造项目("p-1", "新名");
        when(service.updateProject(eq("p-1"), anyString(), anyString(), eq((ProjectIsolation) null)))
                .thenReturn(updated);

        controller.update("p-1", new UpdateProjectRequest("新名", "更新", null));

        verify(service).updateProject("p-1", "新名", "更新", null);
    }

    @Test
    void 更新项目_项目不存在_向上抛ProjectNotFoundException交由advice转404() {
        when(service.updateProject(eq("nope"), any(), any(), any()))
                .thenThrow(new ProjectNotFoundException("nope"));

        assertThrows(ProjectNotFoundException.class, () ->
                controller.update("nope", new UpdateProjectRequest("新名", "", "ISOLATED")));
    }

    @Test
    void 创建项目_非法isolation字符串_Controller转400() {
        // ProjectIsolation.fromString("INVALID") 抛 IAE；Controller catch 后转 ResponseStatusException(400)
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.create(new CreateProjectRequest("项目", "", "INVALID")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void 删除项目_代理到service_返回空数据的成功响应() {
        // service.deleteProject 内部级联由 Service 自身覆盖，Controller 只校验代理 + 响应格式
        ApiResponse<Void> resp = controller.delete("p-1");

        verify(service).deleteProject("p-1");
        assertEquals(200, resp.code());
        assertNull(resp.data());
    }

    @Test
    void 删除项目_项目不存在_向上抛ProjectNotFoundException交由advice转404() {
        doThrow(new ProjectNotFoundException("nope")).when(service).deleteProject("nope");

        assertThrows(ProjectNotFoundException.class, () -> controller.delete("nope"));
    }
}
