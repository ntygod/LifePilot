package com.lifepilot.interaction.web.a2ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class A2uiComponentCatalogContractTest {

    @Test
    void backendCatalog_matchesFrontendRegisteredTypes() throws Exception {
        String fileContent = Files.readString(Path.of("zhiwei-web", "src", "components", "a2ui", "componentCatalog.ts"));
        var matcher = Pattern.compile(
                "A2UI_COMPONENT_TYPES\\s*=\\s*\\[(.*?)]\\s+as const",
                Pattern.DOTALL
        ).matcher(fileContent);

        assertThat(matcher.find()).isTrue();

        Set<String> frontendTypes = Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .map(token -> token.replace("'", "").replace("\"", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(frontendTypes).containsExactlyElementsOf(A2uiComponentCatalog.supportedTypes());
    }

}
