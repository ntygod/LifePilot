package com.lifepilot.project.service;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemorySpaceType;
import com.lifepilot.project.exception.ProjectNotFoundException;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    SessionStoreRepository sessionStoreRepository;

    @Mock
    JdbcTemplate jdbcTemplate;

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
    void deleteProject_级联清理会话_记忆实体关系_后删project和space() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1"))
                .thenReturn(List.of("s-1", "s-2"));

        service.deleteProject("p-1");

        // 1) 查出归属此项目的 sessionId → batchDelete 触发 FK CASCADE 清 session 子表
        verify(sessionStoreRepository).findIdsByProjectId("p-1");
        verify(sessionStoreRepository).batchDelete(List.of("s-1", "s-2"));
        // 2) 先清 memory_relations（FK 到 entities 无 CASCADE）再清 memory_entities
        verify(jdbcTemplate).update(eq("DELETE FROM memory_relations WHERE space_id = ?"), eq("ms-1"));
        verify(jdbcTemplate).update(eq("DELETE FROM memory_entities WHERE space_id = ?"), eq("ms-1"));
        // 3) V15 FK RESTRICT：必须先删 project 再删 space
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void deleteProject_无归属会话时_不调用batchDelete() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1"))
                .thenReturn(List.of());

        service.deleteProject("p-1");

        verify(sessionStoreRepository).findIdsByProjectId("p-1");
        verify(sessionStoreRepository, never()).batchDelete(any());
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void deleteProject_项目不存在_抛ProjectNotFoundException() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(ProjectNotFoundException.class, () -> service.deleteProject("nope"));
        verify(projectRepository, never()).deleteById(anyString());
        verifyNoInteractions(sessionStoreRepository);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void updateProject_项目不存在_抛ProjectNotFoundException() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(ProjectNotFoundException.class, () ->
                service.updateProject("nope", "新名", "", ProjectIsolation.ISOLATED));
        verify(projectRepository, never()).update(any());
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
    void updateProject_重名抛异常() {
        Project p = new Project("p-1", "原名", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(projectRepository.existsByNameAndIdNot("重名", "p-1")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () ->
                service.updateProject("p-1", "重名", "", ProjectIsolation.ISOLATED));
        verify(projectRepository, never()).update(any());
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
