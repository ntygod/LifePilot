package com.lifepilot.project.service;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Mock
    KnowledgeBaseManager knowledgeBaseManager;

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

    private KnowledgeBase mockKb(String id, String name) {
        return mockKb(id, name, List.of("project"));
    }

    private KnowledgeBase mockKb(String id, String name, List<String> tags) {
        Instant now = Instant.now();
        return new KnowledgeBase(
                id,
                name,
                "desc",
                null,
                null,
                "smart",
                Map.of(),
                0,
                0,
                tags,
                now,
                now,
                false,
                null,
                List.of()
        );
    }

    @Test
    void createProject_会自动建关联MemorySpace() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockKb("kb-1", "论文 · 项目知识库"));
        Project created = service.createProject("论文", "严谨", ProjectIsolation.ISOLATED);
        assertEquals("ms-1", created.memorySpaceId());
        verify(memorySpaceRepository).ensureProjectSpace(created.id());
        verify(projectRepository).insert(created);
    }

    @Test
    void createProject_自动建默认知识库_并绑定到项目空间() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockKb("kb-1", "论文 · 项目知识库"));

        service.createProject("论文", "", ProjectIsolation.ISOLATED);

        // KB 名称包含项目名，便于后续人工识别
        verify(knowledgeBaseManager).createKnowledgeBase(
                argThat(name -> name != null && name.contains("论文")),
                anyString(), any(), any(), any(), any(), any(), any());
        // KB 必须绑定到项目的 memory_space（Plan 1 spec §3.2："上传后作为项目知识库的初始资料"）
        verify(memorySpaceRepository).attachKnowledgeBase("ms-1", "kb-1");
    }

    @Test
    void createProject_KB管理器未注入时_仅跳过KB创建_项目仍成功() {
        // 构造一个 knowledgeBaseManager = null 的 service，模拟 KB 功能未启用
        ProjectService noKbService = new ProjectService(
                projectRepository, memorySpaceRepository, sessionStoreRepository, jdbcTemplate, null);
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));

        Project created = noKbService.createProject("项目", "", ProjectIsolation.ISOLATED);

        assertNotNull(created);
        verify(projectRepository).insert(created);
        // 未注入 KB 时不应调 attach
        verify(memorySpaceRepository, never()).attachKnowledgeBase(anyString(), anyString());
    }

    @Test
    void createProject_KB创建异常时_项目仍保留_不抛异常() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("向量索引初始化失败"));

        Project created = service.createProject("项目", "", ProjectIsolation.ISOLATED);

        assertNotNull(created);
        verify(projectRepository).insert(created);
        // KB 失败时不 attach
        verify(memorySpaceRepository, never()).attachKnowledgeBase(anyString(), anyString());
    }

    @Test
    void findKnowledgeBaseIds_代理到memorySpaceRepository() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
                .thenReturn(List.of("kb-1", "kb-2"));

        List<String> kbIds = service.findKnowledgeBaseIds("p-1");

        assertEquals(List.of("kb-1", "kb-2"), kbIds);
    }

    @Test
    void findKnowledgeBaseIds_项目不存在_返回空列表() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(List.of(), service.findKnowledgeBaseIds("nope"));
        verifyNoInteractions(memorySpaceRepository);
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
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockKb("kb-1", "项目 · 项目知识库"));
        Project created = service.createProject("项目", null, ProjectIsolation.ISOLATED);
        assertEquals("", created.instructions());
    }

    @Test
    void createProject_空isolation_用默认ISOLATED() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(mockSpace("ms-1"));
        when(knowledgeBaseManager.createKnowledgeBase(
                anyString(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockKb("kb-1", "项目 · 项目知识库"));
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
        // 项目 space 下无 KB 绑定：跳过 Step 0 KB 级联删除
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
                .thenReturn(List.of());

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
        // 无 KB 绑定时 Step 0 不调 deleteKnowledgeBase
        verify(knowledgeBaseManager, never()).deleteKnowledgeBase(anyString());
    }

    @Test
    void deleteProject_级联删除项目默认KB本体() {
        // Step 0：tag=["project"] 的 KB 是 createProject 自动建的默认 KB，必须随项目级联删本体
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1")).thenReturn(List.of());
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
                .thenReturn(List.of("kb-project"));
        when(knowledgeBaseManager.getKnowledgeBase("kb-project"))
                .thenReturn(Optional.of(mockKb("kb-project", "论文 · 项目知识库", List.of("project"))));

        service.deleteProject("p-1");

        // tag 含 "project" → 级联删 KB 本体（CASCADE 清 documents/chunks/向量索引）
        verify(knowledgeBaseManager).deleteKnowledgeBase("kb-project");
        // 然后继续正常的 1-5 步级联
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void deleteProject_不删用户手动挂的非项目KB() {
        // 用户手动挂到项目空间的 KB 没有 "project" tag → 只解绑（FK CASCADE 自动做），不删本体
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1")).thenReturn(List.of());
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
                .thenReturn(List.of("kb-project", "kb-shared"));
        when(knowledgeBaseManager.getKnowledgeBase("kb-project"))
                .thenReturn(Optional.of(mockKb("kb-project", "论文 · 项目知识库", List.of("project"))));
        when(knowledgeBaseManager.getKnowledgeBase("kb-shared"))
                .thenReturn(Optional.of(mockKb("kb-shared", "通用资料库", List.of("shared", "handbook"))));

        service.deleteProject("p-1");

        // 只删项目默认 KB
        verify(knowledgeBaseManager).deleteKnowledgeBase("kb-project");
        // 绝不删用户手动挂的非项目 KB
        verify(knowledgeBaseManager, never()).deleteKnowledgeBase("kb-shared");
    }

    @Test
    void deleteProject_KB缺失时跳过_不影响删除流程() {
        // 绑定表里还有 kb-missing，但 KB 本体已被别的路径先删了 → getKnowledgeBase 返回 empty
        // 此时 Step 0 跳过该项（不抛空指针），主流程继续
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1")).thenReturn(List.of());
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
                .thenReturn(List.of("kb-missing"));
        when(knowledgeBaseManager.getKnowledgeBase("kb-missing"))
                .thenReturn(Optional.empty());

        service.deleteProject("p-1");

        // KB 缺失时不调 delete
        verify(knowledgeBaseManager, never()).deleteKnowledgeBase(anyString());
        // 但后续 Step 1-5 正常执行
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void deleteProject_KB管理器未注入时_跳过Step0_其余级联正常() {
        // 构造 knowledgeBaseManager=null 的 service，模拟极简部署/集成测试场景
        ProjectService noKbService = new ProjectService(
                projectRepository, memorySpaceRepository, sessionStoreRepository, jdbcTemplate, null);
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(sessionStoreRepository.findIdsByProjectId("p-1")).thenReturn(List.of());

        noKbService.deleteProject("p-1");

        // KB 管理器为 null：不查 space 的 KB 绑定，不调 deleteKnowledgeBase
        verify(memorySpaceRepository, never()).findKnowledgeBaseIdsForSpace(anyString());
        verifyNoInteractions(knowledgeBaseManager);
        // Step 1-5 仍正常执行
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
        when(memorySpaceRepository.findKnowledgeBaseIdsForSpace("ms-1"))
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
