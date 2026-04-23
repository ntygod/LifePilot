package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.project.CreateProjectRequest;
import com.lifepilot.interaction.web.model.project.ProjectResponse;
import com.lifepilot.interaction.web.model.project.UpdateProjectRequest;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 项目（Project）REST 端点。
 *
 * <p>提供：创建 / 列表 / 详情 / 更新。Controller 层只做参数规范化与 DTO 映射，
 * 业务与校验委派给 {@link ProjectService}。与其他 Controller 保持风格：
 * 返回 {@link ApiResponse}，错误通过 {@link ResponseStatusException} 或领域
 * 异常（如 {@code ProjectNotFoundException}）抛出，由全局异常处理器统一转换。</p>
 *
 * <p><b>注意</b>：删除（DELETE）端点暂未开放——
 * {@link ProjectService#deleteProject} 仅级联清理 MemorySpace，尚未覆盖
 * conversations 及其子表。该能力将在 Plan 1 Task 16 完成后统一补齐并开放。
 * 在此之前，Controller 层不暴露删除能力以避免产生孤儿 conversations。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@RestController
@RequestMapping("/api/projects")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    /**
     * 创建项目。
     *
     * <p>{@code instructions} 为 null 时规范化为空串；{@code isolation} 为 null
     * 时使用 {@link ProjectIsolation#defaultValue()}（当前为 ISOLATED）。</p>
     */
    @PostMapping
    public ApiResponse<ProjectResponse> create(@RequestBody CreateProjectRequest req) {
        log.debug("创建项目请求: name={}, isolation={}", req.name(), req.isolation());
        try {
            ProjectIsolation isolation = req.isolation() != null
                    ? ProjectIsolation.fromString(req.isolation())
                    : ProjectIsolation.defaultValue();
            String instructions = req.instructions() != null ? req.instructions() : "";
            Project created = service.createProject(req.name(), instructions, isolation);
            return ApiResponse.ok(ProjectResponse.from(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 列出所有项目（按创建时间倒序）。
     */
    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        List<ProjectResponse> items = service.listProjects().stream()
                .map(ProjectResponse::from)
                .toList();
        return ApiResponse.ok(items);
    }

    /**
     * 按 id 获取项目详情。
     *
     * @param id 项目 id
     * @return 项目 DTO；不存在时抛 404
     */
    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(@PathVariable String id) {
        Project p = service.getProject(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "项目不存在：" + id));
        return ApiResponse.ok(ProjectResponse.from(p));
    }

    /**
     * 更新项目 name / instructions / isolation。
     *
     * <p>{@code instructions} / {@code isolation} 为 null 时由 Service 保留原值。
     * 项目不存在时 Service 抛 {@code ProjectNotFoundException}，由 advice 转 404；
     * 重名或非法 isolation 枚举抛 {@link IllegalArgumentException}，此处转 400。</p>
     */
    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(@PathVariable String id,
                                               @RequestBody UpdateProjectRequest req) {
        log.debug("更新项目请求: id={}, name={}", id, req.name());
        try {
            ProjectIsolation isolation = req.isolation() != null
                    ? ProjectIsolation.fromString(req.isolation())
                    : null;
            Project updated = service.updateProject(id, req.name(), req.instructions(), isolation);
            return ApiResponse.ok(ProjectResponse.from(updated));
        } catch (IllegalArgumentException e) {
            // 重名 / 非法 isolation 枚举 → 400；项目不存在由 ProjectNotFoundException 冒泡走 404
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
