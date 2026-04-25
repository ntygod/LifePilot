package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlaywrightPageWrapper} index 方法族真实 Playwright 集成测试。
 *
 * <p>使用 headless Chromium 打开本地 fixture，验证：</p>
 * <ul>
 *   <li>{@code indexInteractiveElements} 返回快照并注入 {@code data-zhiwei-idx}</li>
 *   <li>{@code clickByIndex} / {@code inputByIndex} / {@code hoverByIndex} 走
 *   {@code [data-zhiwei-idx='N']} 选择器定位</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class PlaywrightPageWrapper_index方法测试 {

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void setup() {
        // CI 或未安装 Chromium 的环境下，Playwright 初始化会抛异常 —— 这里降级为跳过整类，
        // 使用 Assumptions.abort 让 JUnit 标记为 SKIPPED 而非 FAILED。
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (Throwable e) {
            org.junit.jupiter.api.Assumptions.abort(
                    "Playwright Chromium 未安装，跳过真机集成测试: " + e.getMessage());
        }
    }

    @AfterAll
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private Page openFixture() {
        var fixture = Paths.get("src/test/resources/fixtures/browser/sample-interactive.html")
                .toAbsolutePath().toUri().toString();
        var page = browser.newContext().newPage();
        page.navigate(fixture);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return page;
    }

    private PlaywrightPageWrapper wrap(Page page) {
        // 包级构造器，延迟设为 0 避免拖慢测试
        return new PlaywrightPageWrapper(page, 0, 0);
    }

    @Test
    void indexInteractiveElements_返回快照并注入_data_zhiwei_idx() {
        var page = openFixture();
        var wrapper = wrap(page);
        var indexer = new InteractiveElementIndexer(new ObjectMapper());

        IndexedSnapshot snap = wrapper.indexInteractiveElements(indexer, false, 200);

        assertThat(snap.elements()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(snap.truncated()).isFalse();
        // fixture 中 link1 是第 0 号
        assertThat(page.locator("[data-zhiwei-idx='0']").count()).isEqualTo(1);
        assertThat(page.locator("[data-zhiwei-idx='0']").getAttribute("id")).isEqualTo("link1");

        page.context().close();
    }

    @Test
    void clickByIndex_能点击注入编号的元素() {
        var page = openFixture();
        var wrapper = wrap(page);
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        wrapper.indexInteractiveElements(indexer, false, 200);

        // btn1 是 index=1（link1=0, btn1=1）
        wrapper.clickByIndex(1);

        // 点击无异常 + 元素确实存在即通过
        assertThat(page.locator("[data-zhiwei-idx='1']").getAttribute("id")).isEqualTo("btn1");

        page.context().close();
    }

    @Test
    void inputByIndex_填入文本框() {
        var page = openFixture();
        var wrapper = wrap(page);
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        wrapper.indexInteractiveElements(indexer, false, 200);

        // input[name=q] 是 index=2
        wrapper.inputByIndex(2, "hello");

        assertThat(page.locator("[data-zhiwei-idx='2']").inputValue()).isEqualTo("hello");

        page.context().close();
    }

    @Test
    void hoverByIndex_不抛异常() {
        var page = openFixture();
        var wrapper = wrap(page);
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        wrapper.indexInteractiveElements(indexer, false, 200);

        // link1 是 index=0
        wrapper.hoverByIndex(0);

        page.context().close();
    }
}
