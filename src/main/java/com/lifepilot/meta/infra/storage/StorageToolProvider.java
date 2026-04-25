package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.tool.BuiltinTool;

import java.util.List;

/**
 * 存储工具提供者。
 *
 * <p>Plan 3 §5.2（2026-04-23）以及后续 Skill v2 fixup（2026-04-24）决定：datastore 工具
 * 从 LLM 工具集完整下架。后端 {@link DataStoreManager} 与配套 REST/Repository/Service
 * 均完整保留（spec §5.3），供内置 Skill 的泛型 CRUD 适配器、知识库同步链路与未来可能
 * 恢复的管理面使用；LLM 不再通过任何 tool 直接接触 datastore。</p>
 *
 * <p>本类保留为骨架 — 未来若决定重启用 datastore 工具集（例如百万级结构化数据高频
 * CRUD 场景），可在此处重新构建并注册；但目前 {@link #buildStorageTools()} 永远返回
 * 空列表。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class StorageToolProvider {

    @SuppressWarnings("unused") // 保留构造参数以匹配 MetaAutoConfiguration 的 bean 装配签名
    private final DataStoreManager dataStoreManager;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public StorageToolProvider(DataStoreManager dataStoreManager, ObjectMapper objectMapper) {
        this.dataStoreManager = dataStoreManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建数据存储工具列表。
     *
     * @return 永远返回空列表 — datastore 工具已下线，LLM 不再通过工具接触 datastore
     */
    public List<BuiltinTool> buildStorageTools() {
        return List.of();
    }
}
