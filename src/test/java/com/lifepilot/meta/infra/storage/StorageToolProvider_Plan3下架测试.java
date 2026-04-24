package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Plan 3 Datastore 用户侧下架验证测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class StorageToolProvider_Plan3下架测试 {

    @Test
    void buildStorageTools_返回空列表_datastore工具已下线() {
        StorageToolProvider provider = new StorageToolProvider(
                mock(DataStoreManager.class), new ObjectMapper());
        assertTrue(provider.buildStorageTools().isEmpty(),
                "Plan 3 §5.2: datastore 工具应从 LLM 工具集下线，buildStorageTools 返回空列表");
    }
}
