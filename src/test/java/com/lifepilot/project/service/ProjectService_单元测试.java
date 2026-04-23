package com.lifepilot.project.service;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProjectService 单元测试。
 *
 * <p>覆盖创建/更新/删除/查询场景，验证 Project ↔ MemorySpace 的联动行为
 * 以及入参规范化（null → 默认值）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class ProjectService_单元测试 {

    @Mock
    ProjectRepository projectRepository;

    @Mock
    MemorySpaceRepository memorySpaceRepository;

    @InjectMocks
    ProjectService service;

    private MemorySpace mockSpace(String id) {
        return new MemorySpace(
                id,
                "project:x",
                MemorySpaceType.PROJECT,
                "项目记忆",
                "PROJECT",
                "x",
                java.util.Map.of(),
                Instant.now(),
                Instant.now());
    }

    @Test
    void createProject_会自动建关联MemorySpace() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        Project created = service.createProject("论文", "严谨", ProjectIsolation.ISOLATED);
        assertEquals("ms-1", created.memorySpaceId());
        verify(memorySpaceRepository).ensureProjectSpace(created.id());
        verify(projectRepository).insert(created);
    }

    @Test
    void createProject_重名抛异常_且不创建MemorySpace() {
        when(projectRepository.existsByName("论文")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () ->
                service.createProject("论文", "", ProjectIsolation.ISOLATED));
        verifyNoInteractions(memorySpaceRepository);
    }

    @Test
    void createProject_空instructions_规范化为空字符串() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        Project created = service.createProject("项目", null, ProjectIsolation.ISOLATED);
        assertEquals("", created.instructions());
    }

    @Test
    void createProject_空isolation_用默认ISOLATED() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        Project created = service.createProject("项目", "", null);
        assertEquals(ProjectIsolation.ISOLATED, created.isolation());
    }

    @Test
    void deleteProject_会级联删除MemorySpace() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        service.deleteProject("p-1");
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void deleteProject_项目不存在_抛异常() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.deleteProject("nope"));
        verify(projectRepository, never()).deleteById(anyString());
    }

    @Test
    void updateProject_可改name_和instructions_和isolation() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        service.updateProject("p-1", "论文 v2", "更严谨", ProjectIsolation.SHARED);
        verify(projectRepository).update(argThat(updated ->
                updated.name().equals("论文 v2")
                        && updated.instructions().equals("更严谨")
                        && updated.isolation() == ProjectIsolation.SHARED
                        && updated.id().equals("p-1")
                        && updated.memorySpaceId().equals("ms-1")
                        && updated.createdAt().equals(p.createdAt())));
    }

    @Test
    void updateProject_isolation为null_保持原值() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        service.updateProject("p-1", "新名", "新指示", null);
        verify(projectRepository).update(argThat(updated ->
                updated.isolation() == ProjectIsolation.ISOLATED));
    }

    @Test
    void getProject_返回Optional() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        assertEquals(p, service.getProject("p-1").orElseThrow());
    }

    @Test
    void listProjects_代理到repository() {
        when(projectRepository.findAll()).thenReturn(java.util.List.of());
        assertEquals(0, service.listProjects().size());
    }
}
