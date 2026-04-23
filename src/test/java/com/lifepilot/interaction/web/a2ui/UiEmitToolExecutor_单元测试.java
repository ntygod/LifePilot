package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultStatus;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UiEmitToolExecutor_单元测试 {

    @Mock SseSessionManager sseManager;

    private final UiEmitTreeCapture treeCapture = new UiEmitTreeCapture();

    @Test
    void 合法组件树成功发送SSE事件() {
        var executor = new UiEmitToolExecutor(sseManager, 50, treeCapture);
        var components = List.of(
                Map.<String, Object>of(
                        "id", "text-1",
                        "type", "Text",
                        "properties", Map.of("text", "你好"),
                        "children", List.of()
                )
        );
        var input = new ToolInput(
                "ui.render",
                Map.of("components", components),
                JsonSchema.empty(),
                null,
                Map.of("streamId", "stream-1", "sessionId", "sess-1", "turnId", "turn-1")
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        verify(sseManager).sendEvent(eq("stream-1"), any(), any());
    }

    @Test
    void 合法组件树成功发送后捕获到组件树() {
        var executor = new UiEmitToolExecutor(sseManager, 50, treeCapture);
        var components = List.of(
                Map.<String, Object>of(
                        "id", "text-1",
                        "type", "Text",
                        "properties", Map.of("text", "你好"),
                        "children", List.of()
                )
        );
        var input = new ToolInput(
                "ui.render",
                Map.of("components", components),
                JsonSchema.empty(),
                null,
                Map.of("streamId", "stream-capture", "sessionId", "sess-1", "turnId", "turn-1")
        );

        executor.execute(input);

        var captured = treeCapture.poll("stream-capture");
        assertThat(captured).isNotNull();
        assertThat(captured.components()).hasSize(1);
        assertThat(captured.components().getFirst().type()).isEqualTo("Text");

        // poll 后应为 null
        assertThat(treeCapture.poll("stream-capture")).isNull();
    }

    @Test
    void 缺少streamId时返回错误() {
        var executor = new UiEmitToolExecutor(sseManager, 50, treeCapture);
        var input = new ToolInput(
                "ui.render",
                Map.of("components", List.of()),
                JsonSchema.empty(),
                null,
                Map.of()
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    }

    @Test
    void 组件类型不在注册列表时返回错误() {
        var executor = new UiEmitToolExecutor(sseManager, 50, treeCapture);
        var components = List.of(
                Map.<String, Object>of(
                        "id", "x-1",
                        "type", "UnknownWidget",
                        "properties", Map.of(),
                        "children", List.of()
                )
        );
        var input = new ToolInput(
                "ui.render",
                Map.of("components", components),
                JsonSchema.empty(),
                null,
                Map.of("streamId", "stream-1", "sessionId", "sess-1", "turnId", "turn-1")
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    }
}
