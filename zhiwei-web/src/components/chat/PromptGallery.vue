<script setup lang="ts">
/**
 * 空态示例 prompt 轻提示。
 *
 * <p>空态对话页输入框下方渲染一组低存在感快捷提示，点击后 emit pick 事件，
 * 由 ChatView 将 content 灌入 ChatInput。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { computed } from 'vue'
import { BASE_EMPTY_PROMPT_SUGGESTIONS } from '@/utils/emptyPromptSuggestions'
import type { EmptyPromptSuggestion } from '@/utils/emptyPromptSuggestions'

const props = defineProps<{ suggestions?: EmptyPromptSuggestion[] }>()

const suggestions = computed<EmptyPromptSuggestion[]>(() => props.suggestions ?? BASE_EMPTY_PROMPT_SUGGESTIONS)

const emit = defineEmits<{
  pick: [card: EmptyPromptSuggestion]
}>()

function handlePick(suggestion: EmptyPromptSuggestion) {
  emit('pick', suggestion)
}
</script>

<template>
  <nav class="prompt-gallery" aria-label="对话建议">
    <span class="prompt-gallery__prefix">试试</span>
    <template
      v-for="(suggestion, index) in suggestions"
      :key="suggestion.id"
    >
      <span
        v-if="index > 0"
        class="prompt-gallery__separator"
        aria-hidden="true"
      >
        /
      </span>
      <button
        type="button"
        class="prompt-suggestion"
        :title="suggestion.label"
        @click="handlePick(suggestion)"
      >
        <span class="prompt-suggestion__label">{{ suggestion.label }}</span>
      </button>
    </template>
  </nav>
</template>

<style scoped>
.prompt-gallery {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  width: 100%;
  max-width: 600px;
  min-height: 26px;
  overflow: hidden;
  color: hsl(from var(--muted-foreground) h s l / 0.74);
  font-size: 12px;
  line-height: 1.35;
}

.prompt-gallery__prefix,
.prompt-gallery__separator {
  flex: 0 0 auto;
  color: hsl(from var(--muted-foreground) h s l / 0.52);
}

.prompt-suggestion {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 24px;
  max-width: 100%;
  padding: 0 1px;
  border: 0;
  border-bottom: 1px solid transparent;
  border-radius: 0;
  background: transparent;
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  cursor: pointer;
  transition:
    border-color 160ms ease,
    color 160ms ease;
}

.prompt-suggestion:hover {
  border-color: hsl(from var(--primary) h s l / 0.42);
  color: var(--foreground);
}

.prompt-suggestion:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.prompt-suggestion__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

@media (max-width: 640px) {
  .prompt-gallery {
    justify-content: flex-start;
    overflow-x: auto;
    padding-bottom: 2px;
    scrollbar-width: none;
  }

  .prompt-gallery::-webkit-scrollbar {
    display: none;
  }

  .prompt-suggestion {
    flex: 0 0 auto;
  }
}
</style>
