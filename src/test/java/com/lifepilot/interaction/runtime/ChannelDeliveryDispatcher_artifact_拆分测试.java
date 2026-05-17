package com.lifepilot.interaction.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.artifact.SessionArtifactRepository.SessionArtifactRow;
import com.lifepilot.interaction.config.GatewayDeliveryProperties;
import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.tool.model.ArtifactKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelDeliveryDispatcher} 的 artifact 拆分行为测试。
 *
 * <p>覆盖：多 artifact 拆 deliveries / 大小超限降级提示 / 路径越界跳过 /
 * 物理文件丢失跳过 / artifactRepository 未注入降级 / responseId :partN 命名。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class ChannelDeliveryDispatcher_artifact_拆分测试 {

    @TempDir
    Path workspaceRoot;

    private SessionArtifactRepository artifactRepository;
    private GatewayDeliveryProperties deliveryProperties;
    private ChannelDeliveryDispatcher dispatcher;
    private ChannelInstance instance;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(SessionArtifactRepository.class);
        deliveryProperties = new GatewayDeliveryProperties(
                50, 20, List.of(), List.of(),
                new GatewayDeliveryProperties.PlatformMaxSize(30, 20, 30, 50, 200)
        );
        dispatcher = new ChannelDeliveryDispatcher(
                mock(ChannelRegistry.class),
                mock(ChannelInstanceEventService.class),
                mock(RestClient.class),
                mock(ConnectorManager.class),
                null,
                artifactRepository,
                deliveryProperties,
                workspaceRoot
        );
        instance = new ChannelInstance(
                "inst-1", "feishu-plugin", "feishu", "测试飞书",
                true,
                com.lifepilot.interaction.model.ChannelInstanceStatus.RUNNING,
                Map.of(), Map.of(), Map.of(),
                null, null,
                Instant.now(), Instant.now()
        );
    }

    private ChannelRuntimeEventRequest buildEventRequest(String sessionId) {
        return new ChannelRuntimeEventRequest(
                "evt-1", "msg-1", "user-1", sessionId,
                new ChannelRuntimeEventRequest.Content("text", "Hello", null, null),
                List.of(), Map.of(), null, null, Instant.now()
        );
    }

    private ArtifactRef makeRef(String fileName, String mime, ArtifactKind kind, long size) {
        return new ArtifactRef(UUID.randomUUID().toString(), fileName, mime, kind, size);
    }

    private void mockArtifactRow(ArtifactRef ref, Path filePath) {
        SessionArtifactRow row = new SessionArtifactRow(
                ref.artifactId(), "session-1", "entry-1", "trace-1",
                ref.kind() == ArtifactKind.IMAGE ? "image" : "file",
                ref.fileName(), null, "ACTIVE",
                "{}", Instant.now(), Instant.now()
        );
        when(artifactRepository.findById(ref.artifactId())).thenReturn(Optional.of(row));
        when(artifactRepository.readPayload(ref.artifactId())).thenReturn(Map.of(
                "path", filePath.toString(),
                "fileName", ref.fileName(),
                "mimeType", ref.mimeType(),
                "kind", ref.kind().name(),
                "size", ref.size()
        ));
    }

    @Test
    @DisplayName("无 artifactRefs → 仅返回主 delivery（向后兼容）")
    void 无artifact_仅主消息() {
        var response = GatewayResponse.success(ChannelType.FEISHU,
                new ResponseContent.MarkdownContent("Hello"));

        ChannelRuntimeEventResponse result = dispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        assertThat(result.deliveries()).hasSize(1);
        assertThat(result.deliveries().get(0).content().type()).isEqualTo("markdown");
    }

    @Test
    @DisplayName("两个可投递 artifact → 拆出三条 delivery（主消息 + 2 个 file/image）")
    void 多artifact拆分() throws Exception {
        Path docx = workspaceRoot.resolve("report.docx");
        Files.writeString(docx, "doc content");
        Path png = workspaceRoot.resolve("chart.png");
        Files.writeString(png, "fakepng");

        ArtifactRef refDoc = makeRef("report.docx", "application/octet-stream", ArtifactKind.FILE,
                Files.size(docx));
        ArtifactRef refImg = makeRef("chart.png", "image/png", ArtifactKind.IMAGE,
                Files.size(png));
        mockArtifactRow(refDoc, docx);
        mockArtifactRow(refImg, png);

        var response = GatewayResponse.success(ChannelType.FEISHU,
                        new ResponseContent.MarkdownContent("Generated"))
                .toBuilder()
                .responseId("resp-1")
                .artifactRefs(List.of(refDoc, refImg))
                .build();

        ChannelRuntimeEventResponse result = dispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        assertThat(result.deliveries()).hasSize(3);
        // 主消息
        assertThat(result.deliveries().get(0).responseId()).isEqualTo("resp-1");
        assertThat(result.deliveries().get(0).content().type()).isEqualTo("markdown");
        // 第 1 个 artifact
        assertThat(result.deliveries().get(1).responseId()).isEqualTo("resp-1:part1");
        assertThat(result.deliveries().get(1).content().type()).isEqualTo("file");
        assertThat(result.deliveries().get(1).attachments()).hasSize(1);
        assertThat(result.deliveries().get(1).attachments().get(0).fileName()).isEqualTo("report.docx");
        // 第 2 个 artifact（image kind）
        assertThat(result.deliveries().get(2).responseId()).isEqualTo("resp-1:part2");
        assertThat(result.deliveries().get(2).content().type()).isEqualTo("image");
    }

    @Test
    @DisplayName("artifact 大小超限 → 不投递 + 主消息追加路径降级提示")
    void 大小超限_降级提示() throws Exception {
        // feishu 平台限制 30MB；构造一个 31MB 的 artifact ref
        Path big = workspaceRoot.resolve("huge.bin");
        Files.writeString(big, "x");  // 物理文件不必真大，size 由 ref 字段控制
        long oversize = 31L * 1024 * 1024;
        ArtifactRef refBig = makeRef("huge.bin", "application/octet-stream", ArtifactKind.FILE, oversize);
        mockArtifactRow(refBig, big);

        var response = GatewayResponse.success(ChannelType.FEISHU,
                        new ResponseContent.MarkdownContent("Done"))
                .toBuilder()
                .responseId("resp-2")
                .artifactRefs(List.of(refBig))
                .build();

        ChannelRuntimeEventResponse result = dispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        // 仅主消息，artifact 被跳过
        assertThat(result.deliveries()).hasSize(1);
        // 主消息文本含降级提示
        String mainText = (String) result.deliveries().get(0).content().payload().get("markdown");
        assertThat(mainText).contains("huge.bin");
        assertThat(mainText).contains("超过 feishu");
        assertThat(mainText).contains("本地路径");
    }

    @Test
    @DisplayName("artifact 路径越界 → 跳过该 delivery，主消息正常")
    void 路径越界跳过(@TempDir Path outsideWorkspace) throws Exception {
        Path outside = outsideWorkspace.resolve("escape.txt");
        Files.writeString(outside, "x");
        ArtifactRef ref = makeRef("escape.txt", "text/plain", ArtifactKind.FILE, Files.size(outside));
        mockArtifactRow(ref, outside);

        var response = GatewayResponse.success(ChannelType.FEISHU,
                        new ResponseContent.MarkdownContent("Hello"))
                .toBuilder()
                .responseId("resp-3")
                .artifactRefs(List.of(ref))
                .build();

        ChannelRuntimeEventResponse result = dispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        assertThat(result.deliveries()).hasSize(1);
        assertThat(result.deliveries().get(0).content().type()).isEqualTo("markdown");
    }

    @Test
    @DisplayName("artifact 物理文件丢失 → 跳过该 delivery")
    void 文件丢失跳过() {
        Path missing = workspaceRoot.resolve("ghost.docx");
        ArtifactRef ref = makeRef("ghost.docx", "application/octet-stream", ArtifactKind.FILE, 1024);
        mockArtifactRow(ref, missing);

        var response = GatewayResponse.success(ChannelType.FEISHU,
                        new ResponseContent.MarkdownContent("Hello"))
                .toBuilder()
                .responseId("resp-4")
                .artifactRefs(List.of(ref))
                .build();

        ChannelRuntimeEventResponse result = dispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        assertThat(result.deliveries()).hasSize(1);
    }

    @Test
    @DisplayName("artifactRepository 未注入（5 参构造器）→ 跳过所有 artifact")
    void 旧构造器_跳过artifact() {
        var legacyDispatcher = new ChannelDeliveryDispatcher(
                mock(ChannelRegistry.class),
                mock(ChannelInstanceEventService.class),
                mock(RestClient.class),
                mock(ConnectorManager.class),
                null
        );
        ArtifactRef ref = makeRef("a.docx", "application/octet-stream", ArtifactKind.FILE, 100);

        var response = GatewayResponse.success(ChannelType.FEISHU,
                        new ResponseContent.MarkdownContent("Hi"))
                .toBuilder()
                .artifactRefs(List.of(ref))
                .build();

        ChannelRuntimeEventResponse result = legacyDispatcher.buildEventResponse(
                instance, buildEventRequest("session-1"), response);

        assertThat(result.deliveries()).hasSize(1);
    }
}
