package com.lifepilot.meta.infra.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BrowserSessionManager CDP 上下文选择策略测试。
 *
 * <p>验证：优先选有 page 的 context，其中 page 数最多的；相同 page 数选 list 靠前的；
 * 全部无 page 时退回第 0 个；空列表抛异常。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class BrowserSessionManager_CDP上下文选择测试 {

    /** 基于 BrowserContext.pages().size() 的 page 计数器，模拟真实调用点行为。 */
    private static final ToIntFunction<Object> PAGE_COUNTER =
            ctx -> ((BrowserContext) ctx).pages().size();

    /** 构造一个 pages() 返回指定数量 mock page 的 BrowserContext。 */
    private BrowserContext mockCtx(int pageCount) {
        var ctx = mock(BrowserContext.class);
        var pages = new ArrayList<Page>();
        for (int i = 0; i < pageCount; i++) {
            pages.add(mock(Page.class));
        }
        when(ctx.pages()).thenReturn(pages);
        return ctx;
    }

    @Test
    void 有多个_context_优先选_page_数多的() {
        var a = mockCtx(0);
        var b = mockCtx(3);
        var c = mockCtx(1);
        var selected = BrowserSessionManager.selectActiveContext(List.of(a, b, c), PAGE_COUNTER);
        assertThat(selected).isSameAs(b);
    }

    @Test
    void 全部无_page_退回第_0_个() {
        var a = mockCtx(0);
        var b = mockCtx(0);
        var selected = BrowserSessionManager.selectActiveContext(List.of(a, b), PAGE_COUNTER);
        assertThat(selected).isSameAs(a);
    }

    @Test
    void 只有一个有_page_的_context_选它() {
        var a = mockCtx(0);
        var b = mockCtx(2);
        var selected = BrowserSessionManager.selectActiveContext(List.of(a, b), PAGE_COUNTER);
        assertThat(selected).isSameAs(b);
    }

    @Test
    void 相同_page_数选第一个() {
        var a = mockCtx(2);
        var b = mockCtx(2);
        var selected = BrowserSessionManager.selectActiveContext(List.of(a, b), PAGE_COUNTER);
        assertThat(selected).isSameAs(a);
    }

    @Test
    void 空列表抛异常() {
        assertThatThrownBy(() -> BrowserSessionManager.selectActiveContext(List.of(), PAGE_COUNTER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
