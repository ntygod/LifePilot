package com.lifepilot.project.context;

import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemorySpaceType;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * ProjectContextResolver 单元测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class ProjectContextResolver_单元测试 {

    @Mock ProjectRepository projectRepository;
    @Mock MemorySpaceRepository memorySpaceRepository;
    @InjectMocks ProjectContextResolver resolver;

    private MemorySpace space(String id, MemorySpaceType type) {
        return new MemorySpace(id, "key", type, "name", "owner", "owner-id",
                java.util.Map.of(), Instant.now(), Instant.now());
    }

    @Test
    void projectId为null_返回主账户上下文_isolated为false() {
        when(memorySpaceRepository.ensureDefaultPersonalSpace())
                .thenReturn(space("ms-personal", MemorySpaceType.PERSONAL));
        when(memorySpaceRepository.ensureDefaultExperienceSpace())
                .thenReturn(space("ms-experience", MemorySpaceType.EXPERIENCE));

        ProjectContext ctx = resolver.resolve(null);

        assertNull(ctx.projectId());
        assertNull(ctx.projectSpaceId());
        assertEquals("ms-personal", ctx.personalSpaceId());
        assertEquals("ms-experience", ctx.experienceSpaceId());
        assertFalse(ctx.isolated());
    }

    @Test
    void projectId非null_ISOLATED项目_isolated为true() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED,
                "ms-project", Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(memorySpaceRepository.ensureDefaultPersonalSpace())
                .thenReturn(space("ms-personal", MemorySpaceType.PERSONAL));
        when(memorySpaceRepository.ensureDefaultExperienceSpace())
                .thenReturn(space("ms-experience", MemorySpaceType.EXPERIENCE));

        ProjectContext ctx = resolver.resolve("p-1");

        assertEquals("p-1", ctx.projectId());
        assertEquals("ms-project", ctx.projectSpaceId());
        assertEquals("ms-personal", ctx.personalSpaceId());
        assertEquals("ms-experience", ctx.experienceSpaceId());
        assertTrue(ctx.isolated());
    }

    @Test
    void projectId非null_SHARED项目_isolated为false() {
        Project p = new Project("p-2", "小说", "", ProjectIsolation.SHARED,
                "ms-shared", Instant.now(), Instant.now());
        when(projectRepository.findById("p-2")).thenReturn(Optional.of(p));
        when(memorySpaceRepository.ensureDefaultPersonalSpace())
                .thenReturn(space("ms-personal", MemorySpaceType.PERSONAL));
        when(memorySpaceRepository.ensureDefaultExperienceSpace())
                .thenReturn(space("ms-experience", MemorySpaceType.EXPERIENCE));

        ProjectContext ctx = resolver.resolve("p-2");

        assertEquals("p-2", ctx.projectId());
        assertEquals("ms-shared", ctx.projectSpaceId());
        assertFalse(ctx.isolated());
    }

    @Test
    void projectId不存在_抛异常() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(com.lifepilot.project.exception.ProjectNotFoundException.class,
                () -> resolver.resolve("nope"));
    }
}
