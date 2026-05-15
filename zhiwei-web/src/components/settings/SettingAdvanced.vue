<script setup lang="ts">
/**
 * 高级设置折叠区 —— 用于收纳技术性/低频配置项。
 *
 * 默认折叠，点击展开时有平滑的高度过渡动画。
 * 视觉上与普通 SettingSection 区分：更淡的背景 + 虚线边框 + 折叠指示器。
 */
import { ref } from 'vue'
import { ChevronRight, Settings2 } from 'lucide-vue-next'

defineProps<{
  title?: string
  description?: string
}>()

const expanded = ref(false)
const contentEl = ref<HTMLElement | null>(null)

function toggle() {
  if (!contentEl.value) {
    expanded.value = !expanded.value
    return
  }

  if (expanded.value) {
    // 收起
    const height = contentEl.value.scrollHeight
    contentEl.value.style.height = `${height}px`
    contentEl.value.offsetHeight // force reflow
    contentEl.value.style.transition = 'height 280ms cubic-bezier(0.4, 0, 0.2, 1), opacity 280ms cubic-bezier(0.4, 0, 0.2, 1)'
    contentEl.value.style.height = '0'
    contentEl.value.style.opacity = '0'
    expanded.value = false
    contentEl.value.addEventListener('transitionend', () => {
      if (contentEl.value) {
        contentEl.value.style.transition = ''
        contentEl.value.style.height = ''
        contentEl.value.style.opacity = ''
      }
    }, { once: true })
  } else {
    // 展开
    expanded.value = true
    requestAnimationFrame(() => {
      if (!contentEl.value) return
      const height = contentEl.value.scrollHeight
      contentEl.value.style.height = '0'
      contentEl.value.style.opacity = '0'
      contentEl.value.style.overflow = 'hidden'
      contentEl.value.offsetHeight // force reflow
      contentEl.value.style.transition = 'height 280ms cubic-bezier(0.4, 0, 0.2, 1), opacity 280ms cubic-bezier(0.4, 0, 0.2, 1)'
      contentEl.value.style.height = `${height}px`
      contentEl.value.style.opacity = '1'
      contentEl.value.addEventListener('transitionend', () => {
        if (contentEl.value) {
          contentEl.value.style.transition = ''
          contentEl.value.style.height = ''
          contentEl.value.style.opacity = ''
          contentEl.value.style.overflow = ''
        }
      }, { once: true })
    })
  }
}
</script>

<template>
  <div class="setting-advanced">
    <button
      type="button"
      class="setting-advanced__trigger"
      :aria-expanded="expanded"
      @click="toggle"
    >
      <div class="flex items-center gap-2">
        <Settings2 class="size-4 text-muted-foreground/70" />
        <span class="text-[13px] font-medium text-foreground/80">
          {{ title || '高级选项' }}
        </span>
      </div>
      <div class="flex items-center gap-2">
        <span v-if="description && !expanded" class="hidden text-[12px] text-muted-foreground/60 sm:inline">
          {{ description }}
        </span>
        <ChevronRight
          class="size-3.5 text-muted-foreground/50 transition-transform duration-200"
          :class="{ 'rotate-90': expanded }"
        />
      </div>
    </button>

    <div v-show="expanded" ref="contentEl" class="setting-advanced__content">
      <p v-if="description" class="mb-6 text-[13px] leading-relaxed text-muted-foreground/70">
        {{ description }}
      </p>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.setting-advanced {
  border: 1px dashed hsl(from var(--border) h s l / 0.4);
  border-radius: calc(var(--radius) + 2px);
  background: hsl(from var(--muted) h s l / 0.15);
  overflow: hidden;
}

.setting-advanced__trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0.75rem 1rem;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: background 160ms ease;
}

.setting-advanced__trigger:hover {
  background: hsl(from var(--muted) h s l / 0.25);
}

.setting-advanced__content {
  padding: 0 1rem 1rem;
}
</style>
