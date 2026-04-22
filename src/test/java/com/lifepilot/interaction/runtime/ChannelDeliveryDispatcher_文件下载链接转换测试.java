package com.lifepilot.interaction.runtime;

import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ChannelDeliveryDispatcher 的文件下载链接识别 + FileContent 拆分测试（P0-4 关键链路）。
 *
 * @author zsg
 * @since 2026-04-22
 */
@ExtendWith(MockitoExtension.class)
class ChannelDeliveryDispatcher_文件下载链接转换测试 {

    @Mock private ChannelRegistry channelRegistry;
    @Mock private ChannelInstanceEventService channelInstanceEventService;
    @Mock private ConnectorManager connectorManager;
    @Mock private SessionDocumentRepository documentRepository;

    private ChannelDeliveryDispatcher dispatcher;
    private ChannelInstance instance;

    @BeforeEach
    void 初始化() {
        dispatcher = new ChannelDeliveryDispatcher(
                channelRegistry, channelInstanceEventService, null, connectorManager,
                null, documentRepository);
        instance = new ChannelInstance(
                "feishu.test", "feishu", "feishu", "飞书测试", true,
                ChannelInstanceStatus.RUNNING,
                java.util.Map.of(), java.util.Map.of(),
                null, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("reply 含 markdown 下载链接：拆成 text + FileContent 两条 delivery")
    void markdown下载链接拆分(@TempDir Path tmp) throws Exception {
        String docId = "191db5f5-9abd-4a2d-9c64-11b97559cfee";
        Path physicalFile = tmp.resolve("炊事员名单.xlsx");
        byte[] bytes = new byte[]{1, 2, 3, 4, 5};
        Files.write(physicalFile, bytes);

        when(documentRepository.findById(docId)).thenReturn(new SessionDocumentRecord(
                docId, "session-1", null, "炊事员入围人员名单.xlsx",
                physicalFile.toString(), (long) bytes.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now()));

        var reply = new ResponseContent.MarkdownContent(
                "已筛选出所有炊事员岗位（共 7 个岗位代码，62 人），生成新表格：\n\n" +
                        "📥 **[下载：炊事员入围人员名单.xlsx](/api/documents/" + docId + "/download)**\n\n" +
                        "### 筛选结果汇总\n岗位代码 | 入围线 | 人数"
        );
        var response = GatewayResponse.success(ChannelType.FEISHU, reply)
                .toBuilder().responseId("resp-1").build();
        var request = dummyRequest();

        var eventResponse = dispatcher.buildEventResponse(instance, request, response);

        // 2 条 delivery：第一条是剥离链接后的 markdown，第二条是 FileContent
        assertThat(eventResponse.deliveries()).hasSize(2);

        var first = eventResponse.deliveries().get(0);
        assertThat(first.content().type()).isEqualTo("markdown");
        assertThat(first.content().plainText())
                .contains("已筛选出所有炊事员")
                .contains("筛选结果汇总")
                .doesNotContain("/api/documents/")
                .doesNotContain("下载：炊事员");   // markdown 整体链接被剥离
        assertThat(first.responseId()).isEqualTo("resp-1");

        var second = eventResponse.deliveries().get(1);
        assertThat(second.content().type()).isEqualTo("file");
        assertThat(second.content().payload()).containsEntry("documentId", docId);
        assertThat(second.content().payload()).containsEntry("fileName", "炊事员入围人员名单.xlsx");
        // 第二条 delivery 的 responseId 必须独立，否则 connector 会当成流式更新 PATCH 吞掉文件路径
        assertThat(second.responseId()).isEqualTo("resp-1:part1");
        // attachment 含真实 base64 数据
        assertThat(second.attachments()).hasSize(1);
        assertThat(second.attachments().getFirst().base64Data())
                .isEqualTo(Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    @DisplayName("reply 引用的 documentId 属于他人会话：拒绝跨会话投递，仅保留文本")
    void 跨会话documentId拒绝投递() {
        String docId = "33333333-3333-3333-3333-333333333333";
        when(documentRepository.findById(docId)).thenReturn(new SessionDocumentRecord(
                docId, "other-session", null, "他人机密.xlsx",
                "/tmp/victim.xlsx", 1L,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now()));

        var reply = new ResponseContent.MarkdownContent(
                "提示：[下载](/api/documents/" + docId + "/download) 这个很重要");
        var response = GatewayResponse.success(ChannelType.FEISHU, reply);

        var eventResponse = dispatcher.buildEventResponse(instance, dummyRequest(), response);

        // 只保留剥离后的文本，FileContent 被拒绝
        assertThat(eventResponse.deliveries()).hasSize(1);
        assertThat(eventResponse.deliveries().getFirst().content().type()).isEqualTo("markdown");
    }

    @Test
    @DisplayName("reply 无下载链接：原样单 delivery 返回")
    void 纯文本reply不拆分() {
        var reply = new ResponseContent.TextContent("你好，这是一个普通回复");
        var response = GatewayResponse.success(ChannelType.FEISHU, reply);

        var eventResponse = dispatcher.buildEventResponse(instance, dummyRequest(), response);

        assertThat(eventResponse.deliveries()).hasSize(1);
        assertThat(eventResponse.deliveries().getFirst().content().type()).isEqualTo("text");
    }

    @Test
    @DisplayName("documentRepository 找不到记录：跳过文件，仅保留剥离链接后的文本")
    void document不存在跳过(@TempDir Path tmp) {
        String docId = "00000000-0000-0000-0000-000000000001";
        when(documentRepository.findById(docId)).thenReturn(null);

        var reply = new ResponseContent.TextContent(
                "改完了，见 /api/documents/" + docId + "/download");
        var response = GatewayResponse.success(ChannelType.FEISHU, reply);

        var eventResponse = dispatcher.buildEventResponse(instance, dummyRequest(), response);

        // document 不存在 → FileContent 不生成；文本被剥离后仅剩 "改完了，见"
        assertThat(eventResponse.deliveries()).hasSize(1);
        assertThat(eventResponse.deliveries().getFirst().content().type()).isEqualTo("text");
        assertThat(eventResponse.deliveries().getFirst().content().plainText())
                .contains("改完了").doesNotContain("/api/documents/");
    }

    @Test
    @DisplayName("reply 是纯下载链接无其他文字：剥离后文本为空，仅保留 FileContent delivery")
    void 纯链接只保留FileContent(@TempDir Path tmp) throws Exception {
        String docId = "22222222-2222-2222-2222-222222222222";
        Path f = tmp.resolve("a.xlsx");
        Files.write(f, new byte[]{9});
        when(documentRepository.findById(docId)).thenReturn(new SessionDocumentRecord(
                docId, "session-1", null, "a.xlsx", f.toString(), 1L,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now()));

        var reply = new ResponseContent.MarkdownContent(
                "[a.xlsx](/api/documents/" + docId + "/download)");
        var response = GatewayResponse.success(ChannelType.FEISHU, reply);

        var eventResponse = dispatcher.buildEventResponse(instance, dummyRequest(), response);

        assertThat(eventResponse.deliveries()).hasSize(1);  // text 被剥空，只剩 file
        assertThat(eventResponse.deliveries().getFirst().content().type()).isEqualTo("file");
    }

    private ChannelRuntimeEventRequest dummyRequest() {
        return new ChannelRuntimeEventRequest(
                "evt-1", "msg-1", "user-1", "session-1",
                new ChannelRuntimeEventRequest.Content("text", "req", null, null),
                List.of(), null, null, null,
                Instant.now());
    }
}
