package com.lifepilot.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.lifepilot.tool.registry.DynamicToolRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RemoteAgentToolFactory 单元测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class RemoteAgentToolFactory_单元测试 {

    @Mock A2aClientService clientService;
    @Mock DynamicToolRegistry toolRegistry;
    @Mock ApplicationEventPublisher eventPublisher;

    private RemoteAgentToolFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RemoteAgentToolFactory(clientService, toolRegistry, eventPublisher, new ObjectMapper());
    }

    // ── toToolId 测试 ──

    @Test
    void toToolId_简单英文名() {
        assertThat(RemoteAgentToolFactory.toToolId("MyAgent")).isEqualTo("a2a_remote_my_agent");
    }

    @Test
    void toToolId_空格替换为下划线() {
        assertThat(RemoteAgentToolFactory.toToolId("My Agent")).isEqualTo("a2a_remote_my_agent");
    }

    @Test
    void toToolId_连字符替换为下划线() {
        assertThat(RemoteAgentToolFactory.toToolId("my-agent")).isEqualTo("a2a_remote_my_agent");
    }

    @Test
    void toToolId_驼峰转snake_case() {
        assertThat(RemoteAgentToolFactory.toToolId("myRemoteAgent")).isEqualTo("a2a_remote_my_remote_agent");
    }

    @Test
    void toToolId_特殊字符移除() {
        assertThat(RemoteAgentToolFactory.toToolId("Agent@v2.0!")).isEqualTo("a2a_remote_agentv20");
    }

    @Test
    void toToolId_连续下划线合并() {
        assertThat(RemoteAgentToolFactory.toToolId("my--agent")).isEqualTo("a2a_remote_my_agent");
    }

    @Test
    void toToolId_中文名() {
        // 中文字符被移除（非 a-zA-Z0-9_）
        assertThat(RemoteAgentToolFactory.toToolId("知微Agent")).isEqualTo("a2a_remote_agent");
    }

    // ── extractArtifactText 测试 ──

    @Test
    void extractArtifactText_有Text_Artifact_提取文本() {
        var artifact = new A2aArtifact("art-1",
                List.of(new A2aPart.Text("结果内容", null)), null, null);
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, List.of(artifact), null);

        assertThat(factory.extractArtifactText(task)).isEqualTo("结果内容");
    }

    @Test
    void extractArtifactText_无Artifact_从status_message提取() {
        var statusMsg = new A2aMessage("smsg-1", A2aRole.AGENT,
                List.of(new A2aPart.Text("状态消息", null)), null, null, null);
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, statusMsg, Instant.now().toString()),
                null, null, null);

        assertThat(factory.extractArtifactText(task)).isEqualTo("状态消息");
    }

    @Test
    void extractArtifactText_无Artifact无statusMessage_返回无输出() {
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, null, null);

        assertThat(factory.extractArtifactText(task)).isEqualTo("无输出");
    }

    @Test
    void extractArtifactText_File_Artifact_返回文件描述() {
        var fileContent = new A2aFileContent("report.pdf", "application/pdf", null, "https://example.com/report.pdf");
        var artifact = new A2aArtifact("art-1",
                List.of(new A2aPart.File(fileContent, null)), null, null);
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, List.of(artifact), null);

        assertThat(factory.extractArtifactText(task)).contains("文件").contains("report.pdf");
    }

    @Test
    void extractArtifactText_Data_Artifact_返回JSON() {
        var artifact = new A2aArtifact("art-1",
                List.of(new A2aPart.Data(Map.of("key", "value"), null)), null, null);
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, List.of(artifact), null);

        String result = factory.extractArtifactText(task);
        assertThat(result).contains("key").contains("value");
    }

    @Test
    void extractArtifactText_混合类型Artifact_全部提取() {
        var artifact = new A2aArtifact("art-1",
                List.of(
                        new A2aPart.Text("文本结果", null),
                        new A2aPart.File(new A2aFileContent("data.csv", null, "base64", null), null)
                ), null, null);
        var task = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, List.of(artifact), null);

        String result = factory.extractArtifactText(task);
        assertThat(result).contains("文本结果").contains("文件").contains("data.csv");
    }
}
