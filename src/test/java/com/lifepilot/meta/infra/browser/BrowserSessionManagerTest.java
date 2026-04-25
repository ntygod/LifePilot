package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BrowserSessionManager 单元测试。
 *
 * <p>测试环境可能没有 Playwright，因此重点测试可用性检测和降级行为。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class BrowserSessionManagerTest {

    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
    }

    @Test
    void isAvailable_返回布尔值不抛异常() {
        var manager = new BrowserSessionManager(properties);
        // 不论 Playwright 是否在 classpath 上，isAvailable() 都不应抛异常
        boolean available = manager.isAvailable();
        assertThat(available).isIn(true, false);
    }

    @Test
    void getUnavailableMessage_API缺失时提示安装Playwright() {
        var manager = new BrowserSessionManager(properties,
                "浏览器功能未配置，请安装 Playwright",
                mock(BrowserSessionManager.BrowserRuntime.class));
        assertThat(manager.isAvailable()).isFalse();
        assertThat(manager.getUnavailableMessage()).contains("Playwright");
    }

    @Test
    void getUnavailableMessage_缺少驱动包时包含安装指引() {
        String driverMissingReason = "Playwright API 已就绪，但缺少浏览器驱动包（driver-bundle）";
        var manager = new BrowserSessionManager(properties, driverMissingReason,
                mock(BrowserSessionManager.BrowserRuntime.class));
        assertThat(manager.isAvailable()).isFalse();
        assertThat(manager.getUnavailableMessage()).contains("driver-bundle");
    }

    @Test
    void getActiveSessionCount_初始为零() {
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.getActiveSessionCount()).isZero();
    }

    @Test
    void close_无活跃会话时不抛异常() {
        var manager = new BrowserSessionManager(properties);
        // close() 在无活跃会话时应安全执行
        manager.close();
        assertThat(manager.getActiveSessionCount()).isZero();
    }

    @Test
    void isAvailable_禁用浏览器时返回false() {
        properties.getInfra().getBrowser().setEnabled(false);
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.isAvailable()).isFalse();
    }

    @Test
    void cleanupIdleSessions_无会话时不抛异常() {
        var manager = new BrowserSessionManager(properties);
        manager.cleanupIdleSessions();
        assertThat(manager.getActiveSessionCount()).isZero();
    }

    @Test
    void 同一会话多标签页应共享BrowserContext与存储() {
        BrowserContext sharedContext = mock(BrowserContext.class);
        Map<String, String> sharedLocalStorage = new LinkedHashMap<>();
        List<Cookie> sharedCookies = new ArrayList<>();
        when(sharedContext.cookies()).thenAnswer(invocation -> List.copyOf(sharedCookies));
        doAnswer(invocation -> {
            sharedCookies.addAll(invocation.getArgument(0));
            return null;
        }).when(sharedContext).addCookies(anyList());
        doAnswer(invocation -> {
            sharedCookies.clear();
            return null;
        }).when(sharedContext).clearCookies();

        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, sharedLocalStorage, "https://example.com/first", "First"),
                stubPage(sharedContext, sharedLocalStorage, "https://example.com/second", "Second")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        var firstPage = manager.getOrCreatePage("session-1");
        firstPage.setCookie("token", "abc", null, null);
        firstPage.setLocalStorage("theme", "dark");

        String secondTabId = manager.openNewPage("session-1", "https://example.com/second");
        manager.switchPage("session-1", secondTabId);
        var secondPage = manager.getOrCreatePage("session-1");

        assertThat(secondPage.getCookies())
                .extracting(cookie -> cookie.get("name"), cookie -> cookie.get("value"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("token", "abc"));
        assertThat(secondPage.getLocalStorage("theme")).isEqualTo("dark");
        assertThat(runtime.createContextCount).isEqualTo(1);

        manager.closePage("session-1");
        assertThat(runtime.closeContextCount).isEqualTo(1);
    }

    // ==================== CDP 模式测试 ====================

    @Test
    void CDP模式_多会话共享Context_关闭会话不关闭Context() {
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.CDP);
        properties.getInfra().getBrowser().setCdpUrl("http://localhost:9222");

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://a.com", "A"),
                stubPage(sharedContext, new LinkedHashMap<>(), "https://b.com", "B")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        // 创建两个会话
        manager.getOrCreatePage("s1");
        manager.getOrCreatePage("s2");
        assertThat(manager.getActiveSessionCount()).isEqualTo(2);

        // 关闭 s1 — 上下文不应被关闭
        manager.closePage("s1");
        assertThat(runtime.closeContextCount).isZero();
        assertThat(manager.getActiveSessionCount()).isEqualTo(1);

        // 关闭 s2 — 上下文仍不关闭（由 close() 统一清理）
        manager.closePage("s2");
        assertThat(runtime.closeContextCount).isZero();

        // 创建上下文次数应为 0（使用共享上下文）
        assertThat(runtime.createContextCount).isZero();
    }

    @Test
    void CDP模式_cdpUrl为空时抛异常() {
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.CDP);
        properties.getInfra().getBrowser().setCdpUrl("");

        BrowserContext ctx = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(), ctx);
        var manager = new BrowserSessionManager(properties, null, runtime);

        assertThatThrownBy(() -> manager.getOrCreatePage("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdp-url");
    }

    // ==================== PERSISTENT 模式测试 ====================

    @Test
    void PERSISTENT模式_使用持久上下文_关闭会话不关闭Context() {
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.PERSISTENT);
        properties.getInfra().getBrowser().setUserDataDir("/tmp/test-profile");

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://c.com", "C"),
                stubPage(sharedContext, new LinkedHashMap<>(), "https://d.com", "D")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("s1");
        manager.getOrCreatePage("s2");

        // 关闭会话不关闭上下文
        manager.closePage("s1");
        manager.closePage("s2");
        assertThat(runtime.closeContextCount).isZero();

        // launchPersistentContext 应被调用，而非 launchBrowser
        assertThat(runtime.launchPersistentContextCount).isEqualTo(1);
        assertThat(runtime.launchBrowserCount).isZero();

        // close() 统一清理持久上下文
        manager.close();
        assertThat(runtime.closeContextCount).isEqualTo(1);
    }

    @Test
    void PERSISTENT模式_userDataDir为空时抛异常() {
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.PERSISTENT);
        properties.getInfra().getBrowser().setUserDataDir("");

        BrowserContext ctx = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(), ctx);
        var manager = new BrowserSessionManager(properties, null, runtime);

        assertThatThrownBy(() -> manager.getOrCreatePage("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user-data-dir");
    }

    // ==================== User-Agent 动态解析测试 ====================

    @Test
    void LAUNCH模式_默认auto配置时按Chromium版本拼UA传给createContext() {
        // MetaProperties 默认 userAgent="auto"，stub browser.version()=135.0.7000.0
        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://x.com", "X")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("ua-launch");

        assertThat(runtime.lastCreateContextUserAgent)
                .isNotNull()
                .contains("Chrome/135.0.7000.0 Safari/537.36")
                .startsWith("Mozilla/5.0");
    }

    @Test
    void LAUNCH模式_显式UA配置时原样透传给createContext() {
        String customUa = "MyCustomBot/2.0 (+https://example.com)";
        properties.getInfra().getBrowser().setUserAgent(customUa);

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://x.com", "X")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("ua-custom");

        assertThat(runtime.lastCreateContextUserAgent).isEqualTo(customUa);
    }

    @Test
    void PERSISTENT模式_默认auto配置时传null使Chromium真实UA生效() {
        // PERSISTENT 模式无独立 Browser 对象，auto 下 resolveUserAgent 返回 null
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.PERSISTENT);
        properties.getInfra().getBrowser().setUserDataDir("/tmp/ua-profile");

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://x.com", "X")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("ua-persistent");

        assertThat(runtime.lastPersistentContextUserAgent).isNull();
    }

    @Test
    void PERSISTENT模式_显式UA配置时仍原样透传() {
        String customUa = "PersistentBot/1.0";
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.PERSISTENT);
        properties.getInfra().getBrowser().setUserDataDir("/tmp/ua-profile");
        properties.getInfra().getBrowser().setUserAgent(customUa);

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://x.com", "X")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("ua-persistent-custom");

        assertThat(runtime.lastPersistentContextUserAgent).isEqualTo(customUa);
    }

    // ==================== 共享上下文标签页保护测试 ====================

    @Test
    void 共享Context模式_关闭最后标签页不关闭Context() {
        properties.getInfra().getBrowser().setAcquisitionMode(BrowserAcquisitionMode.CDP);
        properties.getInfra().getBrowser().setCdpUrl("http://localhost:9222");

        BrowserContext sharedContext = mock(BrowserContext.class);
        var runtime = new StubBrowserRuntime(List.of(
                stubPage(sharedContext, new LinkedHashMap<>(), "https://e.com", "E"),
                stubPage(sharedContext, new LinkedHashMap<>(), "https://f.com", "F")
        ), sharedContext);
        var manager = new BrowserSessionManager(properties, null, runtime);

        manager.getOrCreatePage("s1");
        String tab2 = manager.openNewPage("s1", "https://f.com");
        assertThat(manager.listPages("s1")).hasSize(2);

        // 关闭两个标签页
        String firstTabId = manager.listPages("s1").stream()
                .filter(t -> !t.tabId().equals(tab2)).findFirst().get().tabId();
        manager.closeTab("s1", firstTabId);
        manager.closeTab("s1", tab2);

        // 所有标签页关闭后，会话移除但上下文不关闭
        assertThat(manager.getActiveSessionCount()).isZero();
        assertThat(runtime.closeContextCount).isZero();
    }

    // ==================== 辅助方法 ====================

    private Page stubPage(BrowserContext browserContext,
                          Map<String, String> localStorage,
                          String initialUrl,
                          String title) {
        AtomicReference<String> currentUrl = new AtomicReference<>(initialUrl);

        Page page = mock(Page.class);
        when(page.context()).thenReturn(browserContext);
        when(page.title()).thenReturn(title);
        when(page.url()).thenAnswer(invocation -> currentUrl.get());
        when(page.navigate(anyString())).thenAnswer(invocation -> {
            currentUrl.set(invocation.getArgument(0));
            return null;
        });
        when(page.evaluate(anyString(), any())).thenAnswer(invocation -> {
            String expression = invocation.getArgument(0, String.class);
            Object argument = invocation.getArgument(1);
            if ("key => localStorage.getItem(key)".equals(expression)) {
                return localStorage.get(argument);
            }
            if ("([k, v]) => localStorage.setItem(k, v)".equals(expression)) {
                List<?> args = (List<?>) argument;
                localStorage.put((String) args.getFirst(), (String) args.get(1));
                return null;
            }
            return null;
        });
        when(page.evaluate("() => localStorage.clear()")).thenAnswer(invocation -> {
            localStorage.clear();
            return null;
        });
        return page;
    }

    private static final class StubBrowserRuntime implements BrowserSessionManager.BrowserRuntime {

        private final Browser browser = mock(Browser.class);
        private final ArrayDeque<Page> pages;
        private final BrowserContext sharedContext;
        private int createContextCount;
        private int closeContextCount;
        private int launchBrowserCount;
        private int launchPersistentContextCount;
        /** 记录最后一次 createContext 收到的 userAgent 参数，供 UA 动态解析测试断言。 */
        private String lastCreateContextUserAgent;
        /** 记录最后一次 launchPersistentContext 收到的 userAgent 参数。 */
        private String lastPersistentContextUserAgent;

        private StubBrowserRuntime(List<Page> pages, BrowserContext sharedContext) {
            this.pages = new ArrayDeque<>(pages);
            this.sharedContext = sharedContext;
            when(browser.newContext()).thenReturn(sharedContext);
            when(sharedContext.newPage()).thenAnswer(invocation -> this.pages.removeFirst());
        }

        @Override
        public Object createPlaywright() {
            return new Object();
        }

        @Override
        public Object launchBrowser(Object playwrightObj, boolean headless, List<String> extraArgs) {
            launchBrowserCount++;
            return browser;
        }

        @Override
        public Object connectOverCDP(Object playwrightObj, String cdpUrl) {
            return browser;
        }

        @Override
        public Object launchPersistentContext(Object playwrightObj, Path userDataDir, boolean headless,
                                              List<String> extraArgs, String userAgent,
                                              int viewportWidth, int viewportHeight,
                                              String locale, String timezoneId) {
            launchPersistentContextCount++;
            lastPersistentContextUserAgent = userAgent;
            return sharedContext;
        }

        @Override
        public List<Object> getContexts(Object browserObj) {
            return List.of(sharedContext);
        }

        @Override
        public int getPageCount(Object browserContextObj) {
            // 桩行为：只有一个 sharedContext，page 数无需真实统计，返回 0 即可走兜底分支
            return 0;
        }

        @Override
        public String getBrowserVersion(Object browserObj) {
            // 测试桩 — 返回固定版本号用于 UA 拼接验证
            return "135.0.7000.0";
        }

        @Override
        public Object createContext(Object browserObj, String userAgent, int viewportWidth, int viewportHeight,
                                    String locale, String timezoneId, Path storageStatePath) {
            createContextCount++;
            lastCreateContextUserAgent = userAgent;
            return sharedContext;
        }

        @Override
        public void injectStealthScripts(Object browserContextObj, String locale) {
            // 测试桩 — 不注入脚本
        }

        @Override
        public void saveStorageState(Object browserContextObj, Path path) {
            // 测试桩 — 不保存 storageState
        }

        @Override
        public Object createPage(Object browserContextObj) {
            return ((BrowserContext) browserContextObj).newPage();
        }

        @Override
        public void closeContext(Object browserContextObj) {
            closeContextCount++;
        }

        @Override
        public void closeBrowser(Object browserObj) {
            // no-op
        }

        @Override
        public void closePlaywright(Object playwrightObj) {
            // no-op
        }
    }
}
