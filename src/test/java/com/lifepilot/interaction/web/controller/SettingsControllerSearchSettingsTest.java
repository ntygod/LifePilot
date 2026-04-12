package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SettingsController} 联网搜索配置接口测试。
 *
 * @author zsg
 * @since 2026-03-20
 */
@ExtendWith(MockitoExtension.class)
class SettingsControllerSearchSettingsTest {

    @Mock
    private UserSettingsRepository settingsRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MetaProperties metaProperties = new MetaProperties();
        var workspaceResolver = new WorkspaceResolver(null, "");
        var controller = new SettingsController(
                settingsRepository,
                new ObjectMapper(),
                null,
                metaProperties,
                workspaceResolver
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 获取联网搜索配置_回退默认值() throws Exception {
        when(settingsRepository.getSearchConfig()).thenReturn("{}");

        mockMvc.perform(get("/api/settings/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider", is("tavily")))
                .andExpect(jsonPath("$.data.apiKey", is("")))
                .andExpect(jsonPath("$.data.maxResults", is(5)))
                .andExpect(jsonPath("$.data.searchDepth", is("basic")))
                .andExpect(jsonPath("$.data.topic", is("general")))
                .andExpect(jsonPath("$.data.includeAnswer", is(true)))
                .andExpect(jsonPath("$.data.connectTimeoutSeconds", is(10)))
                .andExpect(jsonPath("$.data.readTimeoutSeconds", is(30)));
    }

    @Test
    void 更新联网搜索配置_保留已掩码的ApiKey() throws Exception {
        AtomicReference<String> persistedJson = new AtomicReference<>("""
                {"apiKey":"tvly-secret","maxResults":5}
                """);
        when(settingsRepository.getSearchConfig()).thenAnswer(invocation -> persistedJson.get());
        doAnswer(invocation -> {
            persistedJson.set(invocation.getArgument(0));
            return null;
        }).when(settingsRepository).saveSearchConfig(anyString());

        mockMvc.perform(put("/api/settings/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "apiKey": "****cret",
                                    "maxResults": 10,
                                    "searchDepth": "advanced",
                                    "topic": "news",
                                    "includeAnswer": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider", is("tavily")))
                .andExpect(jsonPath("$.data.apiKey", is("****cret")))
                .andExpect(jsonPath("$.data.maxResults", is(10)))
                .andExpect(jsonPath("$.data.searchDepth", is("advanced")))
                .andExpect(jsonPath("$.data.topic", is("news")))
                .andExpect(jsonPath("$.data.includeAnswer", is(false)));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(settingsRepository).saveSearchConfig(jsonCaptor.capture());

        String savedJson = jsonCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(savedJson).contains("\"apiKey\":\"tvly-secret\"");
        org.assertj.core.api.Assertions.assertThat(savedJson).contains("\"maxResults\":10");
        org.assertj.core.api.Assertions.assertThat(savedJson).contains("\"searchDepth\":\"advanced\"");
        org.assertj.core.api.Assertions.assertThat(savedJson).contains("\"topic\":\"news\"");
        org.assertj.core.api.Assertions.assertThat(savedJson).contains("\"includeAnswer\":false");
    }
}
