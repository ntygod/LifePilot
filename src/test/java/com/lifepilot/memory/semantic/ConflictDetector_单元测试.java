package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ConflictDetector 三级冲突检测引擎单元测试。
 *
 * <p>覆盖精确匹配、语义匹配、LLM 消歧义三个阶段的正常路径与降级行为。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConflictDetector 三级冲突检测")
class ConflictDetector_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private VectorSearcher vectorSearcher;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    /** 语义匹配阈值 */
    private static final float SEMANTIC_THRESHOLD = 0.85f;
    private static final String TEST_SPACE_ID = "space-test";

    private ConflictDetector detector;

    /** 构造测试用 TemporalEntity */
    private static TemporalEntity buildEntity(String id, String name, EntityType type, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                id, type, name, description,
                Map.of(), 1, true,
                now, null, null,
                0.9f, 0.5f, 0, null, now, now
        );
    }

    /** 构造测试用 LlmResponse */
    private static LlmResponse buildLlmResponse(String content) {
        return LlmResponse.simple(content, 100, 50, "test-provider", "test-model", 200L);
    }

    // ------------------------------------------------------------------
    // 第一级：精确匹配
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("第一级：精确匹配")
    class 精确匹配阶段 {

        @BeforeEach
        void 初始化() {
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
        }

        @Test
        void 精确匹配命中时直接返回_不进入语义匹配() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "产品经理");
            var existingEntity = buildEntity("existing-1", "张三", EntityType.PERSON, "产品经理");
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("张三"), eq("PERSON"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(existingEntity));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("existing-1");
            verifyNoInteractions(vectorSearcher);
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 精确匹配带_spaceId_命中() {
            // given
            var newEntity = buildEntity("new-1", "项目Alpha", EntityType.PROJECT, "内部项目");
            var existingEntity = buildEntity("existing-1", "项目Alpha", EntityType.PROJECT, "内部项目");
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("项目Alpha"), eq("PROJECT"), eq("space-1")))
                    .thenReturn(List.of(existingEntity));

            // when
            var result = detector.detectConflict(newEntity, "space-1");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("existing-1");
            verifyNoInteractions(vectorSearcher);
        }

        @Test
        void 精确匹配未命中时进入语义匹配() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "产品经理");
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("张三"), eq("PERSON"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of());

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
            verify(vectorSearcher).searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD));
        }

        @Test
        void 无参数重载_缺少spaceId_直接跳过冲突检测() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "产品经理");

            // when
            var result = detector.detectConflict(newEntity);

            // then — 缺少写入空间时 fail-closed，避免跨空间合并
            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(vectorSearcher);
        }
    }

    // ------------------------------------------------------------------
    // 第二级：语义匹配
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("第二级：语义匹配")
    class 语义匹配阶段 {

        @BeforeEach
        void 初始化() {
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
            // 精确匹配一律未命中
            lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
        }

        @Test
        void 语义匹配命中_LLM确认相同实体_返回冲突() {
            // given
            var newEntity = buildEntity("new-1", "小张", EntityType.PERSON, "产品部的张三");
            var candidateEntity = buildEntity("existing-1", "张三", EntityType.PERSON, "产品经理");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.92f)));
            // findEntityById 查询
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidateEntity));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断这两个实体是否为同一实体");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("{\"isSame\": true, \"confidence\": 0.85}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("existing-1");
        }

        @Test
        void 语义匹配命中_LLM判定不同实体_返回空() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "设计师");
            var candidateEntity = buildEntity("existing-1", "张三丰", EntityType.PERSON, "武当山道长");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.88f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidateEntity));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断这两个实体是否为同一实体");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("{\"isSame\": false, \"confidence\": 0.9}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 语义匹配返回空列表_无冲突() {
            // given
            var newEntity = buildEntity("new-1", "全新实体", EntityType.TOPIC, "前所未见的话题");
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of());

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 语义匹配候选实体_findEntityById_查无此人_跳过该候选() {
            // given
            var newEntity = buildEntity("new-1", "测试实体", EntityType.TOPIC, "测试");
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("ghost-id", 0.95f)));
            // findEntityById 返回空 — 该向量索引指向的实体已被删除
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("ghost-id"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of());

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 语义匹配多个候选_第一个被LLM否决_第二个被确认() {
            // given
            var newEntity = buildEntity("new-1", "小张", EntityType.PERSON, "前端开发");
            var candidate1 = buildEntity("c1", "张伟", EntityType.PERSON, "后端开发");
            var candidate2 = buildEntity("c2", "张三", EntityType.PERSON, "前端工程师");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(
                            new VectorSearchResult("c1", 0.93f),
                            new VectorSearchResult("c2", 0.90f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("c1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate1));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("c2"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate2));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断这两个实体是否为同一实体");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    // 第一次 LLM 否决，第二次确认
                    .thenReturn(buildLlmResponse("{\"isSame\": false, \"confidence\": 0.7}"))
                    .thenReturn(buildLlmResponse("{\"isSame\": true, \"confidence\": 0.8}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("c2");
        }
    }

    // ------------------------------------------------------------------
    // 第三级：LLM 消歧义
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("第三级：LLM 消歧义")
    class LLM消歧义阶段 {

        @BeforeEach
        void 初始化() {
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
            // 精确匹配未命中
            lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
        }

        @Test
        void LLM返回_isSame_true但置信度低于0_6_不视为冲突() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "未知身份");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "武术家");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.87f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("{\"isSame\": true, \"confidence\": 0.5}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — confidence < 0.6 所以不算冲突
            assertThat(result).isEmpty();
        }

        @Test
        void LLM返回_isSame_true且置信度恰好0_6_视为冲突() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "开发者");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "程序员");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.90f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("{\"isSame\": true, \"confidence\": 0.6}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — confidence == 0.6，恰好达标
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("existing-1");
        }

        @Test
        void LLM返回非JSON文本_包含true_降级为文本匹配_视为冲突() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "工程师");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "程序员");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.90f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("Yes, they are the same. Result: TRUE"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — 文本包含 "true"（大小写不敏感），视为冲突
            assertThat(result).isPresent();
        }

        @Test
        void LLM返回非JSON文本_不包含true_不视为冲突() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "设计师");
            var candidate = buildEntity("existing-1", "张三丰", EntityType.PERSON, "道长");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.88f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("No, these are different people."));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void LLM返回空isSame字段_默认false_不视为冲突() {
            // given
            var newEntity = buildEntity("new-1", "李四", EntityType.PERSON, "销售");
            var candidate = buildEntity("existing-1", "李四", EntityType.PERSON, "市场");

            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.91f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenReturn(buildLlmResponse("{\"confidence\": 0.8}"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — isSame 缺失默认 false
            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 降级行为
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("降级行为")
    class 降级行为 {

        @Test
        void 无_GenerationRouter时_语义匹配超阈值直接视为冲突_不调用LLM() {
            // given — 构造时 generationRouter 为 null
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, null, SEMANTIC_THRESHOLD, promptRegistry);
            var newEntity = buildEntity("new-1", "小张", EntityType.PERSON, "前端");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "前端开发");

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.92f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("existing-1");
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 语义匹配异常时降级为仅精确匹配_返回空() {
            // given
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "测试");

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenThrow(new RuntimeException("Embedding 服务不可用"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — 降级后返回空
            assertThat(result).isEmpty();
        }

        @Test
        void LLM消歧义异常时降级返回空() {
            // given
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "工程师");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "程序员");

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.90f)));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(candidate));
            when(promptRegistry.render(eq("semantic/entity-disambiguation"), any()))
                    .thenReturn("判断prompt");
            when(generationRouter.call(
                    eq("knowledge_extraction"), anyString(), isNull(), isNull(), isNull(),
                    any(), isNull()))
                    .thenThrow(new RuntimeException("LLM 服务超时"));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — LLM 异常降级返回空
            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 综合场景
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("综合场景")
    class 综合场景 {

        @BeforeEach
        void 初始化() {
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, generationRouter, SEMANTIC_THRESHOLD, promptRegistry);
        }

        @Test
        void 全部三级均未命中_返回空() {
            // given
            var newEntity = buildEntity("new-1", "全新概念", EntityType.CUSTOM, "从未出现过的实体");

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of());

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 不同EntityType的精确匹配也能命中() {
            // given — 精确匹配只看 name + type，不同 type 不会命中同一条记录
            var newEntity = buildEntity("new-1", "Python", EntityType.SKILL, "编程语言");
            var existingEntity = buildEntity("existing-1", "Python", EntityType.SKILL, "编程语言技能");

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("Python"), eq("SKILL"), eq(TEST_SPACE_ID)))
                    .thenReturn(List.of(existingEntity));

            // when
            var result = detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo(EntityType.SKILL);
        }

        @Test
        void textRepresentation传给向量检索器() {
            // given
            var newEntity = buildEntity("new-1", "张三", EntityType.PERSON, "产品经理");
            var expectedText = newEntity.textRepresentation();

            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(eq(expectedText), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of());

            // when
            detector.detectConflict(newEntity, TEST_SPACE_ID);

            // then — 验证传给 vectorSearcher 的文本是 textRepresentation()
            verify(vectorSearcher).searchEntities(eq(expectedText), eq(10), eq(SEMANTIC_THRESHOLD));
        }

        @Test
        void 带spaceId的语义匹配和实体查找传递spaceId() {
            // given
            detector = new ConflictDetector(
                    jdbcTemplate, vectorSearcher, null, SEMANTIC_THRESHOLD, promptRegistry);
            var newEntity = buildEntity("new-1", "小张", EntityType.PERSON, "前端");
            var candidate = buildEntity("existing-1", "张三", EntityType.PERSON, "前端开发");

            // 精确匹配未命中
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("小张"), eq("PERSON"), eq("space-A")))
                    .thenReturn(List.of());
            when(vectorSearcher.searchEntities(anyString(), eq(10), eq(SEMANTIC_THRESHOLD)))
                    .thenReturn(List.of(new VectorSearchResult("existing-1", 0.92f)));
            // findEntityById 带 spaceId
            when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq("space-A")))
                    .thenReturn(List.of(candidate));

            // when
            var result = detector.detectConflict(newEntity, "space-A");

            // then
            assertThat(result).isPresent();
            // 验证 findEntityById 调用时传递了 spaceId
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                    eq("existing-1"), eq("space-A"));
        }
    }
}
