package com.lifepilot.interaction.web.a2ui;

import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A2UI 组件目录，后端组件契约的唯一真实来源。
 *
 * <p>运行时校验器和 RESPONDING 阶段提示词均由此目录驱动，确保两者同步演进。</p>
 *
 * @author zsg
 * @since 2026-03-11
 */
public final class A2uiComponentCatalog {

    public record ComponentSpec(
            String type,
            String promptProperties,
            boolean supportsSignal,
            @Nullable String notes
    ) {
    }

    private static final List<ComponentSpec> COMPONENTS = List.of(
            new ComponentSpec("Text", "{text: string, variant?: \"caption\"|\"eyebrow\"|\"title\"|\"heading\"}", false, null),
            new ComponentSpec("Card", "{title?: string, subtitle?: string, elevated?: boolean}", false, "容器组件，通过 children 引用子组件"),
            new ComponentSpec("Button", "{label: string, variant?: \"default\"|\"outline\"|\"ghost\"|\"destructive\", disabled?: boolean}", true, null),
            new ComponentSpec("TextField", "{label?: string, placeholder?: string, value?: string}", true, null),
            new ComponentSpec("List", "{ordered?: boolean}", false, "容器组件，children 应指向 ListItem"),
            new ComponentSpec("ListItem", "{text: string}", true, null),
            new ComponentSpec("DatePicker", "{label?: string, value?: string}", true, null),
            new ComponentSpec("Chip", "{label: string, selected?: boolean}", true, null),
            new ComponentSpec("Divider", "{orientation?: \"horizontal\"|\"vertical\"}", false, null),
            new ComponentSpec("Image", "{src: string, alt?: string, width?: number, height?: number}", false, null),
            new ComponentSpec("Table", "{columns: [{key: string, label: string}], rows: [{[key]: value}]}", false, null),
            new ComponentSpec("CodeBlock", "{code: string, language?: string}", false, null),
            new ComponentSpec("Progress", "{value: number, label?: string}", false, "value 范围 0-100")
    );

    private static final Set<String> SIGNAL_COMPONENT_TYPES = COMPONENTS.stream()
            .filter(ComponentSpec::supportsSignal)
            .map(ComponentSpec::type)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private static final Map<String, Set<String>> ENUM_PROPERTIES = Map.of(
            key("Text", "variant"), Set.of("caption", "eyebrow", "title", "heading"),
            key("Button", "variant"), Set.of("default", "outline", "ghost", "destructive"),
            key("Divider", "orientation"), Set.of("horizontal", "vertical")
    );

    private A2uiComponentCatalog() {
    }

    public static List<ComponentSpec> components() {
        return COMPONENTS;
    }

    public static Set<String> supportedTypes() {
        return COMPONENTS.stream()
                .map(ComponentSpec::type)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean supportsSignal(String type) {
        return SIGNAL_COMPONENT_TYPES.contains(type);
    }

    public static @Nullable Set<String> allowedValues(String type, String property) {
        return ENUM_PROPERTIES.get(key(type, property));
    }

    private static String key(String type, String property) {
        return type + "#" + property;
    }
}
