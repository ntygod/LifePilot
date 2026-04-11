package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.CreateModelServiceRequest;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.ModelServiceTemplate;
import com.lifepilot.modelservice.model.ModelServiceTemplateModel;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.repository.ModelServiceTemplateRepository;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.modelservice.service.ModelServiceRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ModelServiceController 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
@ExtendWith(MockitoExtension.class)
class ModelServiceControllerTest {

    @Mock
    private ModelServiceRepository modelServiceRepository;
    @Mock
    private GenerationSettingsRepository generationSettingsRepository;
    @Mock
    private EmbeddingSettingsRepository embeddingSettingsRepository;
    @Mock
    private RerankSettingsRepository rerankSettingsRepository;
    @Mock
    private ModelServiceTemplateRepository modelServiceTemplateRepository;
    @Mock
    private ModelServiceRegistrationService registrationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        var controller = new ModelServiceController(
                modelServiceRepository,
                generationSettingsRepository,
                embeddingSettingsRepository,
                rerankSettingsRepository,
                modelServiceTemplateRepository,
                registrationService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 查询模板目录_返回数据库模板数据() throws Exception {
        when(modelServiceTemplateRepository.findAll()).thenReturn(List.of(new ModelServiceTemplate(
                "openai",
                "OpenAI",
                ProviderType.OPENAI_COMPATIBLE,
                "官方模板",
                "https://api.openai.com/v1",
                List.of(ModelServiceKind.GENERATION, ModelServiceKind.EMBEDDING),
                60,
                List.of("CHAT", "STRUCTURED_OUTPUT"),
                List.of("chat", "agent_react"),
                true,
                400000,
                List.of(new ModelServiceTemplateModel(
                        "openai",
                        ModelServiceKind.GENERATION,
                        "gpt-5.4",
                        "GPT-5.4（推荐）",
                        true,
                        List.of("CHAT", "STRUCTURED_OUTPUT"),
                        List.of("chat", "agent_react"),
                        true,
                        400000,
                        null,
                        10
                ))
        )));

        mockMvc.perform(get("/api/model-services/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].vendorKey").value("openai"))
                .andExpect(jsonPath("$.data[0].providerType").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data[0].modelOptions[0].value").value("gpt-5.4"));
    }

    @Test
    void 创建模型服务_保存vendorKey并返回响应() throws Exception {
        when(modelServiceTemplateRepository.existsByVendorKey("openai")).thenReturn(true);

        var request = new CreateModelServiceRequest(
                "openai-generation-gpt-5-4",
                "GENERATION",
                "OPENAI_COMPATIBLE",
                "openai",
                "https://api.openai.com/v1",
                "test-key",
                "gpt-5.4",
                60,
                0,
                List.of("chat", "agent_react"),
                List.of("CHAT", "STRUCTURED_OUTPUT", "FUNCTION_CALLING", "STREAMING"),
                true,
                200,
                800,
                400000,
                null,
                true,
                "OpenAI / GPT-5.4",
                "主力生成服务"
        );

        mockMvc.perform(post("/api/model-services")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("openai-generation-gpt-5-4"))
                .andExpect(jsonPath("$.data.vendorKey").value("openai"))
                .andExpect(jsonPath("$.data.type").value("OPENAI_COMPATIBLE"));

        var captor = ArgumentCaptor.forClass(ModelServiceEntity.class);
        verify(modelServiceRepository).save(captor.capture());
        verify(registrationService).registerService(any(ModelServiceEntity.class));
        assertThat(captor.getValue().metadata()).containsEntry("vendorKey", "openai");
        assertThat(captor.getValue().generationCapabilities())
                .contains(GenerationCapability.CHAT, GenerationCapability.STREAMING);
        assertThat(captor.getValue().providerType()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
    }

    @Test
    void 查询模型服务列表_返回vendorKey字段() throws Exception {
        when(modelServiceRepository.findAll()).thenReturn(List.of(new ModelServiceEntity(
                "openai-main",
                ModelServiceKind.GENERATION,
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                null,
                "gpt-5.4",
                60,
                0,
                true,
                List.of("chat"),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STREAMING),
                Map.of("vendorKey", "openai", "supportsStreaming", true),
                "OpenAI 主力",
                "用于主路由"
        )));

        mockMvc.perform(get("/api/model-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].vendorKey").value("openai"))
                .andExpect(jsonPath("$.data[0].modelName").value("gpt-5.4"));
    }
}
