package com.lifepilot.memory.store.support;

import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.vector.SqliteVecInitializer;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆向量组件测试替身。
 *
 * @author zsg
 * @since 2026-07-03
 */
public final class MemoryVectorTestDoubles {

    private MemoryVectorTestDoubles() {
    }

    public static SqliteVecInitializer noopSqliteVecInitializer() {
        return new NoopSqliteVecInitializer();
    }

    public static VectorSearcher emptyVectorSearcher() {
        VectorSearcher searcher = mock(VectorSearcher.class);
        when(searcher.searchEntities(anyString(), anyInt(), anyFloat())).thenReturn(List.of());
        when(searcher.searchEntities(anyString(), anyInt(), anyFloat(),
                org.mockito.ArgumentMatchers.<Set<String>>any())).thenReturn(List.of());
        when(searcher.isVecExtensionLoaded()).thenReturn(false);
        return searcher;
    }

    private static final class NoopSqliteVecInitializer extends SqliteVecInitializer {

        @Override
        public void init() {
            // 测试上下文不加载平台原生 sqlite-vec 资源。
        }

        @Override
        public @Nullable String getExtractedExtensionAbsolutePath() {
            return null;
        }
    }
}
