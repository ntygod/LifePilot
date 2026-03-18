package com.lifepilot.agent.context;

import com.lifepilot.agent.media.MediaDataExtractor;
import net.jqwik.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentLoopContext 请求隔离属性测试 — 验证属性 6。
 *
 * <p>对于任意两次独立的 run/runStreaming 调用，各自创建的 AgentLoopContext 实例
 * 应互不影响 — 一个上下文中添加的 toolMedia 或 injectedEntityIds
 * 不应出现在另一个上下文中。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
class AgentLoopContext属性测试 {

    /**
     * 属性 6: 请求隔离 — 两个独立 AgentLoopContext 的 toolMedia 互不影响。
     */
    @Property(tries = 50)
    void 两个独立上下文的toolMedia互不影响(
            @ForAll("mediaItem") MediaDataExtractor.MediaItem itemA,
            @ForAll("mediaItem") MediaDataExtractor.MediaItem itemB) {

        var ctxA = new AgentLoopContext();
        var ctxB = new AgentLoopContext();

        ctxA.addToolMedia(itemA);
        ctxB.addToolMedia(itemB);

        // A 只包含 itemA
        assertEquals(1, ctxA.getCollectedToolMedia().size(), "ctxA 应只有 1 个媒体项");
        assertEquals(itemA, ctxA.getCollectedToolMedia().getFirst(), "ctxA 应包含 itemA");

        // B 只包含 itemB
        assertEquals(1, ctxB.getCollectedToolMedia().size(), "ctxB 应只有 1 个媒体项");
        assertEquals(itemB, ctxB.getCollectedToolMedia().getFirst(), "ctxB 应包含 itemB");

        // 清空 A 不影响 B
        ctxA.clearToolMedia();
        assertTrue(ctxA.getCollectedToolMedia().isEmpty(), "ctxA 清空后应为空");
        assertEquals(1, ctxB.getCollectedToolMedia().size(), "ctxB 不受 ctxA 清空影响");
    }

    /**
     * 属性 6 补充: 两个独立 AgentLoopContext 的 injectedEntityIds 互不影响。
     */
    @Property(tries = 50)
    void 两个独立上下文的injectedEntityIds互不影响() {
        String idA = "entity-a-" + java.util.UUID.randomUUID();
        String idB = "entity-b-" + java.util.UUID.randomUUID();

        var ctxA = new AgentLoopContext();
        var ctxB = new AgentLoopContext();

        ctxA.addInjectedEntityIds(java.util.List.of(idA));
        ctxB.addInjectedEntityIds(java.util.List.of(idB));

        assertEquals(1, ctxA.getInjectedEntityIds().size());
        assertTrue(ctxA.getInjectedEntityIds().contains(idA));
        assertFalse(ctxA.getInjectedEntityIds().contains(idB));

        assertEquals(1, ctxB.getInjectedEntityIds().size());
        assertTrue(ctxB.getInjectedEntityIds().contains(idB));
        assertFalse(ctxB.getInjectedEntityIds().contains(idA));
    }

    /**
     * 属性 6 补充: getCollectedToolMedia 返回防御性拷贝。
     */
    @Property(tries = 20)
    void getCollectedToolMedia返回防御性拷贝(@ForAll("mediaItem") MediaDataExtractor.MediaItem item) {
        var ctx = new AgentLoopContext();
        ctx.addToolMedia(item);

        var snapshot = ctx.getCollectedToolMedia();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(item),
                "返回的列表应为不可变");
    }

    // ---- Arbitrary 提供器 ----

    @Provide
    Arbitrary<MediaDataExtractor.MediaItem> mediaItem() {
        var mediaType = Arbitraries.of("image/png", "image/jpeg", "application/pdf");
        var encoding = Arbitraries.of("base64");
        var data = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(50);
        var fieldName = Arbitraries.of("screenshot", "image", "file");
        var metadata = Arbitraries.just(Map.<String, Object>of());
        return Combinators.combine(mediaType, encoding, data, fieldName, metadata)
                .as(MediaDataExtractor.MediaItem::new);
    }

    @Provide
    Arbitrary<String> entityId() {
        return Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(20);
    }
}
