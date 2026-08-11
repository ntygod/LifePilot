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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InteractiveElementIndexer} 真实 Playwright 集成测试。
 *
 * <p>使用 headless Chromium 打开本地 fixture，验证：</p>
 * <ul>
 *   <li>可交互元素识别准确（tag/role/onclick/tabindex + cursor）</li>
 *   <li>hidden input 被排除</li>
 *   <li>注入的 {@code data-zhiwei-idx} 属性可被选择器回查</li>
 *   <li>maxElements 截断时 {@code truncated=true} 且 {@code total} 保留全量</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class InteractiveElementIndexer_标号测试 {

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lifepilot.browser.real-tests"),
                "默认跳过真实 Playwright 浏览器测试；需要时使用 -Dlifepilot.browser.real-tests=true 开启");
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

    @Test
    void 扫描_sample_识别5个可交互元素_排除hidden_input() {
        var page = openFixture();
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        IndexedSnapshot snap = indexer.index(page, false, 200);

        List<IndexedElement> els = snap.elements();
        assertThat(els).hasSize(5);
        assertThat(els.get(0).tag()).isEqualTo("a");
        assertThat(els.get(0).id()).isEqualTo("link1");
        assertThat(els.get(1).tag()).isEqualTo("button");
        assertThat(els.get(1).ariaLabel()).isEqualTo("搜索");
        assertThat(els.get(2).tag()).isEqualTo("input");
        assertThat(els.get(2).name()).isEqualTo("q");
        assertThat(snap.truncated()).isFalse();

        page.context().close();
    }

    @Test
    void 注入的_data_zhiwei_idx_可被选择器命中() {
        var page = openFixture();
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        indexer.index(page, false, 200);

        assertThat(page.locator("[data-zhiwei-idx='0']").getAttribute("id")).isEqualTo("link1");

        page.context().close();
    }

    @Test
    void maxElements_截断时_truncated_为_true() {
        var page = openFixture();
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        IndexedSnapshot snap = indexer.index(page, false, 2);

        assertThat(snap.elements()).hasSize(2);
        assertThat(snap.total()).isGreaterThanOrEqualTo(5);
        assertThat(snap.truncated()).isTrue();

        page.context().close();
    }
}
