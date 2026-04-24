<script setup lang="ts">
/**
 * Skill 编辑器组件。
 *
 * 在 {@link MarkdownEditor} 的基础上叠加了针对 SKILL.md 的客户端软校验：
 *   1. frontmatter 中 name 命名合法（正则 `^[a-z0-9][a-z0-9-]{0,62}$`）
 *   2. description 长度 ≤ 1024 字符
 *   3. description 以「当 / 用于 / Use when / Use this when」开头
 *   4. 正文包含「## 适用场景 / ## 不适用场景 / ## 工作流」三节
 *
 * 校验结果仅做黄色提示，不会阻塞 submit —— 最终校验由后端 {@code SkillInstaller} 负责，
 * 保持"硬控制走代码、软引导走 UI"的分工。
 */
import { computed, watch } from 'vue'
import { AlertTriangle, CheckCircle2 } from 'lucide-vue-next'
import MarkdownEditor from '@/components/editor/MarkdownEditor.vue'
import { validateSkillMarkdown } from '@/components/skill/skillValidation'

const props = withDefaults(defineProps<{
  modelValue: string
  readonly?: boolean
  title?: string
  onSave?: (content: string) => Promise<void>
}>(), {
  readonly: false,
  title: 'SKILL.md',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'validity-change': [valid: boolean]
}>()

// ========== 响应式状态 ==========

const issues = computed(() => validateSkillMarkdown(props.modelValue))
const hasIssues = computed(() => issues.value.length > 0)

watch(hasIssues, valid => emit('validity-change', !valid), { immediate: true })

function onContentChange(value: string) {
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="space-y-sm">
    <!-- 软校验提示 -->
    <div
      v-if="hasIssues"
      class="flex items-start gap-sm rounded-lg border border-amber-200/70 bg-amber-50/80 px-md py-sm text-xs text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200"
    >
      <AlertTriangle class="size-4 shrink-0" />
      <div class="flex-1 space-y-xs">
        <p class="font-medium">软校验发现 {{ issues.length }} 条提示（不会阻止保存，但建议在提交前修正）</p>
        <ul class="space-y-xs">
          <li v-for="(issue, index) in issues" :key="index" class="flex items-start gap-xs">
            <span class="mt-xs size-xs shrink-0 rounded-full bg-amber-500/70" aria-hidden="true" />
            <span class="leading-5">{{ issue.message }}</span>
          </li>
        </ul>
      </div>
    </div>

    <div
      v-else
      class="flex items-center gap-sm rounded-lg border border-emerald-200/70 bg-emerald-50/70 px-md py-sm text-xs text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200"
    >
      <CheckCircle2 class="size-4 shrink-0" />
      <span>SKILL.md 结构看上去没问题。</span>
    </div>

    <!-- 复用项目已有的 Monaco + 预览编辑器 -->
    <MarkdownEditor
      :model-value="modelValue"
      :readonly="readonly"
      :title="title"
      :on-save="onSave"
      @update:model-value="onContentChange"
    />
  </div>
</template>
