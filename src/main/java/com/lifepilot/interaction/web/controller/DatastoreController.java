package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.interaction.web.model.ErrorResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Datastore Web API。
 *
 * <p>当前为前端提供 datastore 列表和详情查询能力，
 * 供聊天配置、知识库关联和文档归属入口使用。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@RestController
@RequestMapping("/api/datastores")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DatastoreController {

    private final DataStoreManager dataStoreManager;

    public DatastoreController(DataStoreManager dataStoreManager) {
        this.dataStoreManager = dataStoreManager;
    }

    /**
     * 查询 datastore 列表。
     *
     * @param q 名称或描述关键字（可选）
     * @return datastore 列表
     */
    @GetMapping
    public ResponseEntity<List<Collection>> listDatastores(@RequestParam(required = false) String q) {
        List<Collection> collections = dataStoreManager.listCollections();
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(collections);
        }

        String keyword = q.strip().toLowerCase();
        List<Collection> filtered = collections.stream()
                .filter(collection -> matchesKeyword(collection, keyword))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    /**
     * 查询单个 datastore 详情。
     *
     * @param id datastore ID
     * @return datastore 详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDatastore(@PathVariable String id) {
        return dataStoreManager.getCollection(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "Datastore 不存在: id=" + id, Instant.now())));
    }

    private boolean matchesKeyword(Collection collection, String keyword) {
        if (collection.name() != null && collection.name().toLowerCase().contains(keyword)) {
            return true;
        }
        return collection.description() != null && collection.description().toLowerCase().contains(keyword);
    }
}
