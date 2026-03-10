package com.lifepilot.datastore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通用数据存储配置属性。
 *
 * <p>绑定 {@code lifepilot.datastore} 配置前缀。控制数据存储模块的限额、分页和索引行为。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.datastore")
public class DataStoreProperties {

    /** 模块总开关，默认 true。 */
    private boolean enabled = true;

    /** 最大集合数量，默认 100。 */
    private int maxCollections = 100;

    /** 每个集合最大文档数量，默认 10000。 */
    private int maxDocumentsPerCollection = 10000;

    /** 单个文档最大字节数，默认 65536（64KB）。 */
    private int maxDocumentSizeBytes = 65536;

    /** 默认分页大小，默认 20。 */
    private int defaultPageSize = 20;

    /** 最大分页大小，默认 100。 */
    private int maxPageSize = 100;

    /** 文档数量达到此阈值时创建 Generated Column 索引，默认 100。 */
    private int indexThreshold = 100;
}
