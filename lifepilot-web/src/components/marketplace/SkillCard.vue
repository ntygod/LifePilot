<script setup lang="ts">
import { ref } from 'vue'
import type { SkillPackage, InstallResult, SecurityReport } from '@/types'
import { marketplaceApi } from '@/api/marketplace'
import SecurityReportDialog from './SecurityReportDialog.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  skill: SkillPackage
  /** 是否有可用更新 */
  hasUpdate?: boolean
}>()

const emit = defineEmits<{
  /** 安装/卸载/升级后刷新列表 */
  refresh: []
}>()

// 操作状态
const installing = ref(false)
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
  <Card class="list-card flex flex-col">
    <CardHeader class="pb-2">
      <!-- 头部：名称 + 已验证徽章 -->
      <div class="flex items-start justify-between gap-sm">
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5">
            <h3 class="font-medium text-foreground text-sm leading-snug truncate">
              {{ skill.name }}
            </h3>
            <Badge v-if="skill.verified" variant="secondary" class="shrink-0">
              ✓ 已验证
            </Badge>
          </div>
          <p class="text-xs text-muted-foreground mt-0.5">
            {{ skill.author }} · v{{ skill.version }}
          </p>
        </div>
      </div>
    </CardHeader>

    <CardContent class="flex-1 flex flex-col pb-3">
      <!-- 描述 -->
      <p class="text-xs text-muted-foreground line-clamp-2 leading-normal mb-sm flex-1">
        {{ skill.description || '暂无描述' }}
      </p>

      <!-- 标签 -->
      <div v-if="skill.tags.length > 0" class="flex flex-wrap gap-1 mb-sm">
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

      <!-- 底部：下载量 + 安装状态 + 操作按钮 -->
      <div class="flex items-center justify-between mt-auto pt-sm border-t border-border">
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <span>↓ {{ skill.downloads }}</span>
          <span v-if="skill.installed" class="text-primary">
            已安装 v{{ skill.installedVersion }}
          </span>
        </div>

        <div class="flex items-center gap-1.5">
          <!-- 升级按钮 -->
          <Button
            v-if="skill.installed && hasUpdate"
            size="sm"
            :disabled="upgrading"
            @click="handleUpgrade"
          >
            {{ upgrading ? '升级中...' : '升级' }}
          </Button>

          <!-- 卸载按钮 -->
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

          <!-- 安装按钮 -->
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

      <!-- 错误提示 -->
      <p v-if="errorMsg" class="text-xs text-destructive mt-xs">
        {{ errorMsg }}
      </p>
    </CardContent>

    <!-- 安全报告对话框 -->
    <SecurityReportDialog
      v-if="securityReport"
      v-model:show="showSecurityDialog"
      :report="securityReport"
      :skill-name="skill.name"
      @confirm="confirmHighRiskInstall"
      @cancel="showSecurityDialog = false"
    />

    <!-- 卸载确认对话框 -->
    <ConfirmDialog
      v-model:show="showUninstallConfirm"
      title="确认卸载"
      :message="`确定要卸载「${skill.name}」吗？卸载后将从本地移除该 Skill。`"
      confirm-label="卸载"
      confirm-variant="destructive"
      @confirm="handleUninstall"
    />
  </Card>
</template>
