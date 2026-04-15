<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { Sparkles, Shield, ShieldCheck, ShieldAlert } from 'lucide-vue-next'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { useProactiveStore } from '@/stores/proactive'
import { BEHAVIOR_THEMES, behaviorBadgeStyle } from '@/constants/behaviorTheme'

const store = useProactiveStore()
const { config, configLoading, trustStatuses, trustLoading, pendingUpgrades } = storeToRefs(store)

const saving = ref(false)

// 本地编辑状态
const localEnabled = ref(true)
const localDailyMax = ref(3)
const localQuietStart = ref('')
const localQuietEnd = ref('')

const LEVEL_LABELS: Record<string, string> = { A: '通知型', B: '建议型', C: '代行型' }
const LEVEL_ICONS = { A: Shield, B: ShieldCheck, C: ShieldAlert }

function syncFromConfig() {
  if (!config.value) return
  localEnabled.value = config.value.enabled
  localDailyMax.value = config.value.dailyMaxReminders
  localQuietStart.value = config.value.quietHoursStart ?? ''
  localQuietEnd.value = config.value.quietHoursEnd ?? ''
}

const isDirty = computed(() => {
  if (!config.value) return false
  return localEnabled.value !== config.value.enabled
    || localDailyMax.value !== config.value.dailyMaxReminders
    || localQuietStart.value !== (config.value.quietHoursStart ?? '')
    || localQuietEnd.value !== (config.value.quietHoursEnd ?? '')
})

async function saveConfig() {
  saving.value = true
  await store.updateConfig({
    enabled: localEnabled.value,
    dailyMaxReminders: localDailyMax.value,
    quietHoursStart: localQuietStart.value,  // 空字符串 = 清除，后端区分 null(不变) vs ""(清除)
    quietHoursEnd: localQuietEnd.value,
  })
  saving.value = false
}

async function handleConfirmUpgrade(behavior: string) {
  await store.confirmUpgrade(behavior)
}

function getTrustForBehavior(name: string) {
  return trustStatuses.value.find(t => t.behaviorName === name)
}

onMounted(async () => {
  await Promise.all([store.fetchConfig(), store.fetchTrustStatus()])
  syncFromConfig()
})
</script>

<template>
  <div class="space-y-xl">
    <!-- 全局控制 -->
    <SettingSection title="全局控制" description="管理主动助手的整体行为">
      <SettingItem label="主动助手" description="启用后，微微会主动追问进展、推送洞察和生成日报">
        <Switch v-model:checked="localEnabled" />
      </SettingItem>

      <SettingItem label="每日推送上限" description="每天最多主动推送的次数">
        <Input
          v-model.number="localDailyMax"
          type="number"
          :min="1" :max="20"
          class="w-20"
        />
      </SettingItem>

      <SettingItem label="静默时段" description="此时段内不会主动打扰">
        <div class="flex items-center gap-sm">
          <Input v-model="localQuietStart" type="time" class="w-28" placeholder="23:00" />
          <span class="text-muted-foreground">至</span>
          <Input v-model="localQuietEnd" type="time" class="w-28" placeholder="08:00" />
        </div>
      </SettingItem>

      <div v-if="isDirty" class="flex justify-end">
        <Button size="sm" :disabled="saving" @click="saveConfig">
          {{ saving ? '保存中...' : '保存' }}
        </Button>
      </div>
    </SettingSection>

    <!-- 行为管理 -->
    <SettingSection title="行为插件" description="每种行为有独立的自主度等级">
      <div class="space-y-sm">
        <div
          v-for="theme in BEHAVIOR_THEMES"
          :key="theme.key"
          class="flex items-center gap-md rounded-lg border px-md py-sm"
        >
          <!-- 行为色片 -->
          <span
            class="inline-flex shrink-0 items-center rounded-md px-sm text-xs font-semibold leading-6"
            :style="behaviorBadgeStyle(theme.key)"
          >
            {{ theme.label }}
          </span>

          <div class="flex-1" />

          <!-- 当前自主度等级 -->
          <template v-if="getTrustForBehavior(theme.key)">
            <component
              :is="LEVEL_ICONS[getTrustForBehavior(theme.key)!.currentLevel] ?? Shield"
              class="size-4 text-muted-foreground"
            />
            <span class="text-sm text-muted-foreground">
              {{ LEVEL_LABELS[getTrustForBehavior(theme.key)!.currentLevel] ?? 'A' }}
            </span>
          </template>
          <template v-else>
            <Shield class="size-4 text-muted-foreground" />
            <span class="text-sm text-muted-foreground">通知型</span>
          </template>
        </div>
      </div>
    </SettingSection>

    <!-- 待确认的信任升级 -->
    <SettingSection
      v-if="pendingUpgrades.length > 0"
      title="升级建议"
      description="这些行为表现良好，可以升级自主度"
    >
      <div class="space-y-sm">
        <div
          v-for="upgrade in pendingUpgrades"
          :key="upgrade.behaviorName"
          class="flex items-center gap-md rounded-lg border border-primary/20 bg-primary/5 px-md py-sm"
        >
          <span
            class="inline-flex shrink-0 items-center rounded-md px-sm text-xs font-semibold leading-6"
            :style="behaviorBadgeStyle(upgrade.behaviorName)"
          >
            {{ upgrade.behaviorLabel }}
          </span>

          <span class="flex-1 text-sm">
            {{ LEVEL_LABELS[upgrade.currentLevel] }}
            &rarr;
            {{ LEVEL_LABELS[upgrade.targetLevel ?? 'B'] }}
          </span>

          <span class="text-xs text-muted-foreground">
            连续 {{ upgrade.consecutivePositive }} 次正反馈
          </span>

          <Button size="sm" @click="handleConfirmUpgrade(upgrade.behaviorName)">
            同意升级
          </Button>
        </div>
      </div>
    </SettingSection>
  </div>
</template>
