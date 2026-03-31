package com.lifepilot.meta.infra.storage;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

/**
 * StorageToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class StorageToolProviderTest {

    @Test
    void createCollection工具应接受结构化属性并返回默认知识库ID() {
        DataStoreManager dataStoreManager = mock(DataStoreManager.class);
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        StorageToolProvider provider = new StorageToolProvider(dataStoreManager);

        provider.buildStorageTools().forEach(registry::registerBuiltinTool);

        var created = new Collection(
                "ds-1",
                "novel-workspace",
                "小说创作数据存储",
                CollectionType.DOCUMENT,
                null,
                "{}",
                null,
                "kb-1",
                null,
                "2026-03-27T00:00:00Z",
                "2026-03-27T00:00:00Z"
        );
        when(dataStoreManager.createCollection(
                eq("novel-workspace"),
                eq(CollectionType.DOCUMENT),
                any(),
                eq("小说创作数据存储"),
                eq(null),
                eq(null)
        )).thenReturn(created);

        var tool = registry.resolve("datastore").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of(
                        "action", "create-collection",
                        "name", "novel-workspace",
                        "type", "DOCUMENT",
                        "description", "小说创作数据存储",
                        "properties", List.of(
                                Map.of("name", "title", "type", "TEXT", "required", true),
                                Map.of("name", "chapter", "type", "INTEGER", "required", false)
                        )
                ),
                tool.inputSchema(),
                null,
                null
        ));

        assertThat(result.ok()).isTrue();
        assertThat(result.<String>getData("defaultKnowledgeBaseId")).isEqualTo("kb-1");

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(dataStoreManager).createCollection(
                eq("novel-workspace"),
                eq(CollectionType.DOCUMENT),
                captor.capture(),
                eq("小说创作数据存储"),
                eq(null),
                eq(null)
        );
        @SuppressWarnings("unchecked")
        List<PropertyDefinition> propDefs = captor.getValue();
        assertThat(propDefs).hasSize(2);
        assertThat(propDefs.get(1).type()).isEqualTo(PropertyType.NUMBER);
    }

    @Test
    void deleteCollection工具应按集合名称删除集合() {
        DataStoreManager dataStoreManager = mock(DataStoreManager.class);
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        StorageToolProvider provider = new StorageToolProvider(dataStoreManager);

        provider.buildStorageTools().forEach(registry::registerBuiltinTool);

        var collection = new Collection(
                "ds-1",
                "dev-workspace",
                "开发资料",
                CollectionType.DOCUMENT,
                null,
                "{}",
                null,
                null,
                "2026-03-27T00:00:00Z",
                "2026-03-27T00:00:00Z"
        );
        when(dataStoreManager.findCollection("dev-workspace")).thenReturn(Optional.of(collection));
        when(dataStoreManager.deleteCollection("ds-1")).thenReturn(true);

        var tool = registry.resolve("datastore").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "delete-collection", "collectionName", "dev-workspace"),
                tool.inputSchema(),
                null,
                null
        ));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Boolean>getData("deleted")).isTrue();
        assertThat(result.<String>getData("id")).isEqualTo("ds-1");
        assertThat(result.<String>getData("name")).isEqualTo("dev-workspace");
        verify(dataStoreManager).deleteCollection("ds-1");
    }
}
