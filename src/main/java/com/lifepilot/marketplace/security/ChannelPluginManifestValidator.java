package com.lifepilot.marketplace.security;

import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ChannelPluginResources;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.RiskLevel;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 渠道插件 manifest 校验器。
 *
 * <p>统一校验 Marketplace 安装的 {@code channel-plugin.json} 是否满足最小结构约束，
 * 避免任意 JSON 直接进入渠道控制面。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class ChannelPluginManifestValidator {

    private static final List<String> RESERVED_PLATFORMS = List.of(
            "cron", "workflow", "heartbeat", "eval", "a2a", "web-test"
    );

    private ChannelPluginManifestValidator() {
    }

    public static List<ValidationIssue> validateForMarketplace(ExtensionPackage pkg,
                                                               ChannelPluginDescriptor descriptor) {
        List<ValidationIssue> issues = new ArrayList<>();
        requireText(descriptor.pluginId(), "pluginId", issues);
        requireText(descriptor.name(), "name", issues);
        requireText(descriptor.platform(), "platform", issues);
        requireText(descriptor.version(), "version", issues);

        if (!Objects.equals(pkg.id(), descriptor.pluginId())) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "Manifest 字段",
                    "packageId 与 pluginId 不一致: packageId=%s, pluginId=%s"
                            .formatted(pkg.id(), descriptor.pluginId())
            ));
        }
        if (!Objects.equals(pkg.version(), descriptor.version())) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "Manifest 字段",
                    "索引版本与 manifest 版本不一致: packageVersion=%s, manifestVersion=%s"
                            .formatted(pkg.version(), descriptor.version())
            ));
        }

        if (descriptor.platform() != null && RESERVED_PLATFORMS.contains(descriptor.platform().toLowerCase(Locale.ROOT))) {
            issues.add(new ValidationIssue(
                    RiskLevel.HIGH,
                    "平台保留字",
                    "platform 使用了系统保留标识: " + descriptor.platform()
            ));
        }

        if (descriptor.connectorMode() != ConnectorMode.EXTERNAL) {
            issues.add(new ValidationIssue(
                    RiskLevel.HIGH,
                    "运行时模式",
                    "Marketplace 渠道插件只允许 EXTERNAL connector，当前为: " + descriptor.connectorMode()
            ));
        }

        validateConfigSchema(descriptor, issues);
        validateConnectorSpec(descriptor, issues);
        validateCapabilities(descriptor, issues);
        validateResources(descriptor.resources(), issues);
        return List.copyOf(issues);
    }

    private static void validateConfigSchema(ChannelPluginDescriptor descriptor,
                                             List<ValidationIssue> issues) {
        Map<String, Object> configSchema = descriptor.configSchema();
        if (configSchema.isEmpty()) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "配置 Schema",
                    "缺少 configSchema 定义"
            ));
            return;
        }

        Object schemaType = configSchema.get("type");
        if (schemaType != null && !"object".equals(String.valueOf(schemaType))) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "配置 Schema",
                    "configSchema.type 必须为 object"
            ));
        }

        Object propertiesRaw = configSchema.get("properties");
        if (!(propertiesRaw instanceof Map<?, ?> properties)) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "配置 Schema",
                    "configSchema.properties 必须为对象"
            ));
            return;
        }

        for (String secretField : descriptor.secretFields()) {
            if (secretField == null || secretField.isBlank()) {
                issues.add(new ValidationIssue(
                        RiskLevel.LOW,
                        "配置 Schema",
                        "secretFields 中包含空字段名"
                ));
                continue;
            }
            if (!properties.containsKey(secretField)) {
                issues.add(new ValidationIssue(
                        RiskLevel.MEDIUM,
                        "配置 Schema",
                        "secretFields 引用了未声明的字段: " + secretField
                ));
            }
        }

        Object requiredRaw = configSchema.get("required");
        if (requiredRaw instanceof List<?> requiredFields) {
            requiredFields.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(field -> !properties.containsKey(field))
                    .forEach(field -> issues.add(new ValidationIssue(
                            RiskLevel.MEDIUM,
                            "配置 Schema",
                            "required 引用了未声明的字段: " + field
                    )));
        }
    }

    private static void validateConnectorSpec(ChannelPluginDescriptor descriptor,
                                              List<ValidationIssue> issues) {
        Map<String, Object> configSchema = descriptor.configSchema();
        Map<String, Object> properties = readProperties(configSchema);
        boolean hasBaseUrlField = properties.containsKey("baseUrl") || properties.containsKey("connectorBaseUrl");
        boolean hasConnectorAddress = hasText(descriptor.connectorSpec(), "baseUrl")
                || hasText(descriptor.connectorSpec(), "url");
        boolean hasManagedSpec = hasManagedSpec(descriptor.connectorSpec());
        boolean hasProtocol = hasText(descriptor.connectorSpec(), "protocol");

        if (!hasBaseUrlField && !hasConnectorAddress && !hasManagedSpec) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "Connector 描述",
                    "外部渠道插件必须提供 baseUrl/connectorBaseUrl 配置字段，在 connectorSpec 中声明 url/baseUrl，或提供 managed 托管描述"
            ));
        }
        if (!hasProtocol) {
            issues.add(new ValidationIssue(
                    RiskLevel.LOW,
                    "Connector 描述",
                    "建议在 connectorSpec 中声明 protocol"
            ));
        }
        validateManagedConnectorSpec(descriptor.connectorSpec(), issues);
    }

    private static void validateCapabilities(ChannelPluginDescriptor descriptor,
                                             List<ValidationIssue> issues) {
        if (descriptor.capabilities().isEmpty()) {
            issues.add(new ValidationIssue(
                    RiskLevel.LOW,
                    "能力声明",
                    "capabilities 为空，无法清晰表达插件能力面"
            ));
            return;
        }
        if (descriptor.capabilities().stream().anyMatch(capability -> capability == null || capability.isBlank())) {
            issues.add(new ValidationIssue(
                    RiskLevel.LOW,
                    "能力声明",
                    "capabilities 中包含空白项"
            ));
        }
    }

    private static void validateResources(@Nullable ChannelPluginResources resources,
                                          List<ValidationIssue> issues) {
        if (resources == null) {
            return;
        }
        validateResourcePath(resources.readmePath(), "resources.readmePath", issues);
        validateResourcePath(resources.iconPath(), "resources.iconPath", issues);
        resources.examplePaths().forEach(path -> validateResourcePath(path, "resources.examplePaths", issues));
        resources.assetPaths().forEach(path -> validateResourcePath(path, "resources.assetPaths", issues));
    }

    private static void validateResourcePath(@Nullable String value,
                                             String fieldName,
                                             List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Path normalized = Path.of(value).normalize();
            String text = normalized.toString();
            if (normalized.isAbsolute()
                    || value.startsWith("/")
                    || value.startsWith("\\")
                    || value.matches("^[A-Za-z]:.*")
                    || text.isBlank()
                    || text.startsWith("..")) {
                issues.add(new ValidationIssue(
                        RiskLevel.MEDIUM,
                        "资源路径",
                        fieldName + " 只能使用插件目录内的相对路径: " + value
                ));
            }
        } catch (InvalidPathException e) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "资源路径",
                    fieldName + " 包含非法路径字符: " + value
            ));
        }
    }

    private static Map<String, Object> readProperties(Map<String, Object> configSchema) {
        Object properties = configSchema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue
                    ));
        }
        return Map.of();
    }

    private static void requireText(@Nullable String value,
                                    String fieldName,
                                    List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(new ValidationIssue(
                    RiskLevel.MEDIUM,
                    "Manifest 字段",
                    fieldName + " 不能为空"
            ));
        }
    }

    private static boolean hasText(@Nullable Map<String, Object> map, String key) {
        if (map == null) {
            return false;
        }
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private static boolean hasManagedSpec(@Nullable Map<String, Object> connectorSpec) {
        if (connectorSpec == null) {
            return false;
        }
        Object managed = connectorSpec.get("managed");
        if (!(managed instanceof Map<?, ?> raw)) {
            return false;
        }
        Object strategy = raw.get("strategy");
        return strategy instanceof String text && !text.isBlank();
    }

    private static void validateManagedConnectorSpec(@Nullable Map<String, Object> connectorSpec,
                                                     List<ValidationIssue> issues) {
        if (connectorSpec == null) {
            return;
        }
        Object managed = connectorSpec.get("managed");
        if (!(managed instanceof Map<?, ?> raw)) {
            return;
        }
        Object workspace = raw.get("workspace");
        if (workspace instanceof String text && !text.isBlank()) {
            validateResourcePath(text, "connectorSpec.managed.workspace", issues);
        }
        Object artifactPath = raw.get("artifactPath");
        if (artifactPath instanceof String text && !text.isBlank()) {
            validateResourcePath(text, "connectorSpec.managed.artifactPath", issues);
        }
    }

    /**
     * manifest 校验问题。
     */
    public record ValidationIssue(
            RiskLevel level,
            String category,
            String message
    ) {
    }
}
