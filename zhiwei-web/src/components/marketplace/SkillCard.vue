<script setup lang="ts">
import { ref } from 'vue'
import type { ExtensionPackage, InstallResult, SecurityReport } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
import SecurityReportDialog from './SecurityReportDialog.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  skill: ExtensionPackage
  /** 是否有可用更新 */
  hasUpdate?: boolean
}>()

const emit = defineEmits<{
  /** 安装/卸载/升级后刷新列表 */
  refresh: []
}>()

// 操作状态
const installing = ref(false)

// 扩展类型标签映射
const typeLabel: Record<string, string> = {
  SKILL: '技能',
  AGENT: '智能体',
  WORKFLOW: '工作流',
}

const typeVariant: Record<string, 'default' | 'secondary' | 'outline'> = {
  SKILL: 'default',
  AGENT: 'secondary',
  WORKFLOW: 'outline',
}
const uninstalling = ref(false)
const upgrading = ref(false)
const errorMsg = ref('')

// 安全报告对话框
const showSecurityDialog = ref(false)
const securityReport = ref<SecurityReport | null>(null)

// 卸载确认对话框
const showUninstallConfirm = ref(false)

/** 安装 Skill */
async function handleInstall() {
  installing.value = true
  errorMsg.value = ''
  try {
    const result: InstallResult = await marketplaceApi.install(props.skill.id)
    if (result.requiresConfirmation && result.securityReport) {
      securityReport.value = result.securityReport
      showSecurityDialog.value = true
    } else if (result.success) {
      emit('refresh')
    } else {
      errorMsg.value = result.errorMessage || '安装失败'
    }
  } catch (e: any) {
    errorMsg.value = e.message || '安装失败'
  } finally {
    installing.value = false
  }
}

/** 确认高风险安装 */
async function confirmHighRiskInstall() {
  showSecurityDialog.value = false
  installing.value = true
  errorMsg.value = ''
  try {
    const result = await marketplaceApi.install(props.skill.id, true)
    if (result.success) {
      emit('refresh')
    } else {
      errorMsg.value = result.errorMessage || '安装失败'
    }
  } catch (e: any) {
    errorMsg.value = e.message || '安装失败'
  } finally {
    installing.value = false
  }
}

/** 卸载 Skill */
async function handleUninstall() {
  showUninstallConfirm.value = false
  uninstalling.value = true
  errorMsg.value = ''
  try {
    await marketplaceApi.uninstall(props.skill.id)
    emit('refresh')
  } catch (e: any) {
    errorMsg.value = e.message || '卸载失败'
  } finally {
    uninstalling.value = false
  }
}

/** 升级 Skill */
async function handleUpgrade() {
  upgrading.value = true
  errorMsg.value = ''
  try {
    const result = await marketplaceApi.upgrade(props.skill.id)
    if (result.requiresConfirmation && result.securityReport) {
      securityReport.value = result.securityReport
      showSecurityDialog.value = true
    } else if (result.success) {
      emit('refresh')
    } else {
      errorMsg.value = result.errorMessage || '升级失败'
    }
  } catch (e: any) {
    errorMsg.value = e.message || '升级失败'
  } finally {
    upgrading.value = false
  }
}
</script>

<template>
  <article class="list-card flex h-full flex-col p-5">
    <div class="space-y-4">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0 flex-1 space-y-2">
          <div class="flex flex-wrap items-center gap-2">
            <h3 class="truncate text-base font-semibold tracking-tight text-foreground">
              {{ skill.name }}
            </h3>
            <Badge :variant="typeVariant[skill.type] ?? 'outline'" class="shrink-0 text-[10px]">
              {{ typeLabel[skill.type] ?? skill.type }}
            </Badge>
            <Badge v-if="skill.verified" variant="secondary" class="shrink-0">
              已验证
            </Badge>
            <Badge v-if="hasUpdate" variant="outline" class="shrink-0">
              可更新
            </Badge>
          </div>
          <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
            <span class="surface-chip">作者 {{ skill.author }}</span>
            <span class="surface-chip">版本 v{{ skill.version }}</span>
            <span v-if="skill.installed" class="surface-chip surface-chip-strong">已安装 v{{ skill.installedVersion }}</span>
          </div>
          <p class="line-clamp-3 text-sm leading-6 text-muted-foreground">
            {{ skill.description || '暂无描述' }}
          </p>
        </div>
      </div>

      <div v-if="skill.requirements && skill.requirements.length > 0" class="space-y-2">
        <div class="surface-label text-[0.68rem]">前置条件</div>
        <div class="flex flex-wrap gap-2">
          <Badge v-for="req in skill.requirements" :key="req" variant="outline" class="text-[10px]">
            {{ req }}
          </Badge>
        </div>
      </div>

      <div v-if="skill.tags.length > 0" class="flex flex-wrap gap-2">
        <Badge
          v-for="t in skill.tags.slice(0, 4)"
          :key="t"
          variant="outline"
        >
          {{ t }}
        </Badge>
        <Badge v-if="skill.tags.length > 4" variant="outline">
          +{{ skill.tags.length - 4 }}
        </Badge>
      </div>

      <div class="mt-4 flex items-center justify-between gap-3 border-t border-border/60 pt-4">
        <div class="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
          <span>下载 {{ skill.downloads }}</span>
          <span v-if="hasUpdate">有新版本可升级</span>
        </div>

        <div class="flex items-center gap-1.5">
          <Button
            v-if="skill.installed && hasUpdate"
            size="sm"
            :disabled="upgrading"
            @click="handleUpgrade"
          >
            {{ upgrading ? '升级中...' : '升级' }}
          </Button>

          <Button
            v-if="skill.installed"
            variant="outline"
            size="sm"
            :disabled="uninstalling"
            class="hover:bg-destructive/10 hover:text-destructive hover:border-destructive/50"
            @click="showUninstallConfirm = true"
          >
            {{ uninstalling ? '卸载中...' : '卸载' }}
          </Button>

          <Button
            v-if="!skill.installed"
            size="sm"
            :disabled="installing"
            @click="handleInstall"
          >
            {{ installing ? '安装中...' : '安装' }}
          </Button>
        </div>
      </div>

      <p v-if="errorMsg" class="text-xs text-destructive">
        {{ errorMsg }}
      </p>
    </div>

    <SecurityReportDialog
      v-if="securityReport"
      v-model:show="showSecurityDialog"
      :report="securityReport"
      :skill-name="skill.name"
      @confirm="confirmHighRiskInstall"
      @cancel="showSecurityDialog = false"
    />

    <ConfirmDialog
      v-model:show="showUninstallConfirm"
      title="确认卸载"
      :message="`确定要卸载「${skill.name}」吗？卸载后将从本地移除该技能。`"
      confirm-label="卸载"
      confirm-variant="destructive"
      @confirm="handleUninstall"
    />
  </article>
</template>
