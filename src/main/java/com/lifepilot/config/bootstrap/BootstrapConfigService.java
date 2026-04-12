package com.lifepilot.config.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 引导配置文件读写服务。
 *
 * <p>读写 {@code ~/.zhiwei/config.json}，供设置页面修改数据目录等启动级配置。
 * 修改后需重启应用才能生效。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@Service
public class BootstrapConfigService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfigService.class);
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), ".zhiwei", "config.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取引导配置中的数据目录。
     *
     * @return 用户配置的数据目录，未配置则返回 null
     */
    public String getDataDir() {
        if (!Files.exists(CONFIG_PATH)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(CONFIG_PATH.toFile());
            JsonNode node = root.get("dataDir");
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        } catch (IOException e) {
            log.warn("读取引导配置失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 保存数据目录到引导配置。
     *
     * @param dataDir 数据目录路径，null 或空字符串表示清除自定义设置
     */
    public void saveDataDir(String dataDir) {
        try {
            ObjectNode root;
            if (Files.exists(CONFIG_PATH)) {
                JsonNode existing = objectMapper.readTree(CONFIG_PATH.toFile());
                root = existing.isObject() ? (ObjectNode) existing : objectMapper.createObjectNode();
            } else {
                root = objectMapper.createObjectNode();
                Files.createDirectories(CONFIG_PATH.getParent());
            }

            if (dataDir == null || dataDir.isBlank()) {
                root.remove("dataDir");
            } else {
                root.put("dataDir", dataDir);
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), root);
            log.info("引导配置已保存: dataDir={}", dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("保存引导配置失败: " + e.getMessage(), e);
        }
    }
}
