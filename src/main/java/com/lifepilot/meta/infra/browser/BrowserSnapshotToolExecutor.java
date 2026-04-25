package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;

/**
 * 浏览器页面快照工具 — 一次性返回截图 + 可交互元素标号表。
 *
 * <p>LLM 调用此 action 后，后续 click/input/hover 可用 index 精准定位，
 * 避免猜 CSS 选择器。截图通过 {@code screenshot} 字段自动挂 vision 输入
 * （{@link com.lifepilot.agent.media.MediaDataExtractor} 已识别 screenshot 字段）。</p>
 *
 * <p>字段名固定用 {@code screenshot} — Phase 0 调研发现 MediaDataExtractor 和
 * ToolBridgeAgentToolProvider 已把 {@code screenshot} 作为白名单 key，改名会破坏
 * 已运行的 vision 链路。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class BrowserSnapshotToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserSnapshotToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final InteractiveElementIndexer indexer;
    private final MetaProperties.Infra.Browser.Snapshot config;

    public BrowserSnapshotToolExecutor(@Nullable BrowserSessionManager sessionManager,
                                       InteractiveElementIndexer indexer,
                                       MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.indexer = indexer;
        this.config = properties.getInfra().getBrowser().getSnapshot();
    }

    /**
     * 扫描当前页面可交互元素并返回截图 + 编号表。
     *
     * @param input 工具输入，可选 sessionId / injectLabels / maxElements / viewportOnly
     * @return 成功返回 {@code {screenshot, elements, total, truncated, viewport, url, title}}
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null) {
            return ToolResult.error("浏览器功能未配置");
        }
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        boolean injectLabels = input.getOptionalParam("injectLabels", Boolean.class)
                .orElse(config.isInjectLabels());
        int maxElements = input.getOptionalParam("maxElements", Integer.class)
                .orElse(config.getMaxElements());
        boolean viewportOnly = input.getOptionalParam("viewportOnly", Boolean.class)
                .orElse(config.isViewportOnly());

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            IndexedSnapshot snapshot = page.indexInteractiveElements(indexer, injectLabels, maxElements);

            // 字段名 screenshot 不可改：MediaDataExtractor.KNOWN_MEDIA_FIELDS 硬编码此 key，
            // 改名会破坏 vision 链路。
            String screenshot = page.screenshot(!viewportOnly);

            var result = new LinkedHashMap<String, Object>();
            result.put("screenshot", screenshot);
            result.put("elements", snapshot.elements());
            result.put("total", snapshot.total());
            result.put("truncated", snapshot.truncated());
            result.put("viewport", snapshot.viewport());
            result.put("url", page.url());
            result.put("title", page.title());
            log.debug("浏览器快照完成: sessionId={}, elements={}, total={}", sessionId,
                    snapshot.elements().size(), snapshot.total());
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("浏览器快照失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器快照失败: " + e.getMessage());
        }
    }
}
