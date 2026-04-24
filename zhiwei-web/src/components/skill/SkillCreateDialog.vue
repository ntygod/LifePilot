<script setup lang="ts">
/**
 * 新建 Skill 对话框 —— 基于 SkillEditor 编辑 SKILL.md 并调用 {@code store.createSkill}。
 *
 * 初始模板包含完整的 frontmatter 骨架 + 三节必备小节，帮助用户快速上手。
 * 软校验失败不会阻塞提交，最终由后端 SkillInstaller 执行硬校验（frontmatter schema + parser）。
 */
import { ref, watch } from 'vue'
import { Loader2, Plus } from 'lucide-vue-next'
import FormDialogShell from '@/components/common/FormDialogShell.vue'
import SkillEditor from '@/components/skill/SkillEditor.vue'
import { Button } from '@/components/ui/button'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import type { SkillInstallation } from '@/types'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  created: [installation: SkillInstallation]
}>()

const store = useSkillStore()
const uiStore = useUiStore()

const DEFAULT_TEMPLATE = `---
name: my-skill
description: 当...时使用。关键词 ...
version: 1.0.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags: []
    suggested_tools: []
    requires:
      bins: []
      env: []
      os: []
      tools: []
---

## 适用场景
-

## 不适用场景
-

## 工作流
1.
`

const content = ref(DEFAULT_TEMPLATE)
const submitting = ref(false)

watch(() => props.open, open => {
  if (open) {
    content.value = DEFAULT_TEMPLATE
    submitting.value = false
  }
})

function close() {
  if (submitting.value) return
  emit('update:open', false)
}

async function submit() {
  if (submitting.value) return

  if (!content.value.trim()) {
    uiStore.showToast('error', 'SKILL.md 内容不能为空。')
    return
  }

  submitting.value = true
  try {
    const installation = await store.createSkill({ skillMdContent: content.value })
    uiStore.showToast('success', `技能「${installation.name}」创建成功。`)
    emit('created', installation)
    emit('update:open', false)
  } catch (error: any) {
    uiStore.showToast('error', error?.message ?? '创建技能失败。')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <FormDialogShell
    :open="props.open"
    title="新建技能"
    description="编辑 SKILL.md 创建一个新的技能定义。保存后会走标准安装流水线，来源标记为「导入」。"
    content-class="sm:max-w-[68rem]"
    body-class="space-y-md"
    prevent-outside-close
    @update:open="value => emit('update:open', value)"
    @close="close"
  >
    <SkillEditor v-model="content" title="SKILL.md" />

    <template #footer>
      <div class="flex items-center justify-between gap-sm">
        <p class="text-xs text-muted-foreground">
          提交后后端会解析 frontmatter、执行硬校验并落盘到 skills 目录。
        </p>
        <div class="flex items-center gap-sm">
          <Button variant="outline" :disabled="submitting" @click="close">
            取消
          </Button>
          <Button :disabled="submitting" @click="submit">
            <Loader2 v-if="submitting" class="h-sm w-sm animate-spin" />
            <Plus v-else class="h-sm w-sm" />
            {{ submitting ? '创建中...' : '创建技能' }}
          </Button>
        </div>
      </div>
    </template>
  </FormDialogShell>
</template>
