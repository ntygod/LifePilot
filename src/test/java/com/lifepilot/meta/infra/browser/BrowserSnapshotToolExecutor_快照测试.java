package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrowserSnapshotToolExecutor 快照测试。
 *
 * <p>验证：返回字段完整（含 screenshot 字段名不变）、sessionManager 缺失返回错误、
 * 入参可覆盖配置默认值。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class BrowserSnapshotToolExecutor_快照测试 {

    @Mock BrowserSessionManager sessionManager;
    @Mock PlaywrightPageWrapper page;
    @Mock InteractiveElementIndexer indexer;

    private MetaProperties properties;

    @BeforeEach
    void init() {
        properties = new MetaProperties();
    }

    @Test
    void 成功返回_screenshot_和_elements() {
        when(sessionManager.getOrCreatePage("task-x")).thenReturn(page);
        var elements = List.of(new IndexedElement(0, "a", "", "首页", "", "home", "",
                new int[]{0, 0, 50, 20}));
        when(page.indexInteractiveElements(eq(indexer), eq(false), eq(200)))
                .thenReturn(new IndexedSnapshot(elements, 1, new int[]{1920, 1080}, false));
        when(page.screenshot(false)).thenReturn("AAAA");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("示例");

        var exec = new BrowserSnapshotToolExecutor(sessionManager, indexer, properties);
        ToolResult result = exec.execute(buildInput(Map.of("sessionId", "task-x")));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> data = result.data();
        assertThat(data).containsKeys("screenshot", "elements", "total", "truncated", "viewport", "url", "title");
        assertThat(data.get("total")).isEqualTo(1);
        assertThat(data.get("truncated")).isEqualTo(false);
        assertThat(data.get("screenshot")).isEqualTo("AAAA");
        assertThat(data.get("url")).isEqualTo("https://example.com");
        assertThat(data.get("title")).isEqualTo("示例");
    }

    @Test
    void sessionManager_不可用_返回错误() {
        var exec = new BrowserSnapshotToolExecutor(null, indexer, properties);
        ToolResult result = exec.execute(buildInput(Map.of()));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("浏览器");
    }

    @Test
    void 用户覆盖_maxElements_和_injectLabels() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.indexInteractiveElements(eq(indexer), eq(true), eq(50)))
                .thenReturn(new IndexedSnapshot(List.of(), 0, new int[]{100, 100}, false));
        when(page.screenshot(true)).thenReturn("B");
        when(page.url()).thenReturn("x");
        when(page.title()).thenReturn("y");

        var exec = new BrowserSnapshotToolExecutor(sessionManager, indexer, properties);
        var result = exec.execute(buildInput(Map.of(
                "injectLabels", true,
                "maxElements", 50,
                "viewportOnly", false
        )));

        assertThat(result.isSuccess()).isTrue();
        verify(page).indexInteractiveElements(indexer, true, 50);
        verify(page).screenshot(true);  // !viewportOnly
    }

    // ─────────────────────────────────────────────
    private static ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("test.browser", params, JsonSchema.empty(), null, null);
    }
}
