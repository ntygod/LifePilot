<script setup lang="ts">
interface Props {
  icon?: string
  title?: string
  description?: string
  actionLabel?: string
  showAction?: boolean
  /** 示例问题列表，点击后触发 example 事件 */
  examples?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  icon: '📭',
  title: '暂无数据',
  description: '',
  showAction: false,
  examples: () => []
})

const emit = defineEmits<{
  action: []
  example: [content: string]
}>()

function handleAction() {
  emit('action')
}

function handleExample(content: string) {
  emit('example', content)
}
</script>

<template>
  <div class="flex flex-col items-center justify-center py-16 px-6 text-center">
    <!-- 渐变背景图标容器 -->
    <div
      v-if="icon"
      class="bg-gradient-to-br from-primary/10 to-primary/5 rounded-full p-4 mb-6"
    >
      <span class="text-4xl block">{{ icon }}</span>
    </div>

    <h3 class="text-lg font-semibold text-foreground mb-3">{{ title }}</h3>

    <p v-if="description" class="text-sm text-muted-foreground mb-6 max-w-[448px] leading-relaxed">
      {{ description }}
    </p>

    <!-- 示例问题按钮 -->
    <div v-if="examples.length > 0" class="flex flex-col gap-2 w-full max-w-[448px] mb-6">
      <button
        v-for="(example, i) in examples"
        :key="i"
        type="button"
        class="w-full rounded-lg border border-dashed border-border px-4 py-2.5 text-left text-sm
               hover:translate-x-1 hover:bg-accent hover:text-accent-foreground
               transition-all duration-200"
        @click="handleExample(example)"
      >
        {{ example }}
      </button>
    </div>

    <button
      v-if="showAction && actionLabel"
      class="h-9 px-4 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
      @click="handleAction"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>
