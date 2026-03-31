package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
    void getUnavailableMessage_返回中文提示() {
        var manager = new BrowserSessionManager(properties);
        assertThat(manager.getUnavailableMessage()).contains("Playwright");
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
        var manager = new BrowserSessionManager(properties, true, runtime);

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
        public Object launchBrowser(Object playwrightObj, boolean headless) {
            return browser;
        }

        @Override
        public Object createContext(Object browserObj) {
            createContextCount++;
            return sharedContext;
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
