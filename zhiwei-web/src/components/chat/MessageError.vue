<script setup lang="ts">
import type { DiagnosticReport, Message } from '@/types'
import { computed, ref, type Component } from 'vue'
import { Activity, AlertCircle, Archive, Bot, Check, Copy, CornerDownRight, Cpu, DatabaseBackup, KeyRound, Loader2, PlugZap, RotateCcw } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import { diagnosticsApi } from '@/api/client'
import { copyToClipboard } from '@/utils/clipboard'
import { buildDiagnosticNextActions, buildDiagnosticRepairLinks, buildErrorDiagnostic, buildMessageRecoveryBrief, buildMessageRepairLinks, type DiagnosticRepairLink } from '@/utils/errorDiagnostic'

const props = defineProps<{
  message: Message
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
}>()

const copied = ref(false)
const creatingBackup = ref(false)
const backupNotice = ref<string | null>(null)
const backupError = ref<string | null>(null)
const analyzingDiagnostic = ref(false)
const diagnosticStatus = ref<string | null>(null)
const diagnosticSummary = ref<string | null>(null)
const diagnosticActions = ref<string[]>([])
const diagnosticError = ref<string | null>(null)
const backupSuggested = ref(false)
const recoveryBrief = computed(() => buildMessageRecoveryBrief(props.message))

interface RepairLinkView extends DiagnosticRepairLink {
  icon: Component
}

const repairLinks = ref<RepairLinkView[]>([])

function normalizeError(event: unknown) {
  if (event instanceof Error) return event.message
  if (typeof event === 'object' && event !== null && 'message' in event) {
    return String((event as { message?: unknown }).message ?? event)
  }
  return String(event)
}

function hasProblemStatus(status: unknown) {
  const normalized = String(status ?? '').toUpperCase()
  return normalized === 'WARN' || normalized === 'ERROR'
}

function reportSuggestsDataProtection(report: DiagnosticReport) {
  const checks = new Map(report.checks.map(check => [check.id, check]))
  return hasProblemStatus(checks.get('data-backups')?.status)
    || String(checks.get('schema-migrations')?.status ?? '').toUpperCase() === 'ERROR'
    || String(checks.get('database')?.status ?? '').toUpperCase() === 'ERROR'
}

function repairLinkIcon(link: DiagnosticRepairLink): Component {
  switch (link.id) {
    case 'models':
      return Bot
    case 'code-execution':
      return Cpu
    case 'channels':
      return PlugZap
    case 'capabilities':
      return PlugZap
    case 'permissions':
      return KeyRound
    case 'general':
      return DatabaseBackup
    default:
      return Activity
  }
}

function toRepairLinkView(link: DiagnosticRepairLink): RepairLinkView {
  return { ...link, icon: repairLinkIcon(link) }
}

function mergeRepairLinks(...groups: RepairLinkView[][]) {
  const links: RepairLinkView[] = []
  for (const group of groups) {
    for (const link of group) {
      if (!links.some(item => item.id === link.id)) {
        links.push(link)
      }
    }
  }
  return links.slice(0, 3)
}

const messageRepairLinks = computed(() =>
  buildMessageRepairLinks(props.message, props.message.errorMessage).map(toRepairLinkView),
)
const visibleRepairLinks = computed(() => mergeRepairLinks(messageRepairLinks.value, repairLinks.value))

function applyDiagnosticReport(report: DiagnosticReport, message: Message) {
  diagnosticStatus.value = report.status
  diagnosticSummary.value = report.summary
  diagnosticActions.value = buildDiagnosticNextActions(report, message.errorMessage).slice(0, 3)
  diagnosticError.value = null
  backupSuggested.value = reportSuggestsDataProtection(report)
  repairLinks.value = buildDiagnosticRepairLinks(report).map(toRepairLinkView)
}

function applyDiagnosticFailure(error: string, message: Message) {
  diagnosticStatus.value = null
  diagnosticSummary.value = null
  diagnosticError.value = error
  repairLinks.value = []
  const actions = buildDiagnosticNextActions(null, message.errorMessage)
  diagnosticActions.value = actions.length > 0
    ? actions.slice(0, 3)
    : ['本机状态暂时读取失败，先复制诊断信息并查看后端日志。']
}

async function analyzeLocalStatus(message: Message) {
  if (analyzingDiagnostic.value) return
  analyzingDiagnostic.value = true
  diagnosticError.value = null
  try {
    const report = await diagnosticsApi.getReport()
    applyDiagnosticReport(report, message)
  } catch (event) {
    applyDiagnosticFailure(normalizeError(event), message)
  } finally {
    analyzingDiagnostic.value = false
  }
}

async function copyDiagnostic(message: Message) {
  let diagnosticReport: DiagnosticReport | null = null
  let diagnosticReportError: string | null = null
  try {
    diagnosticReport = await diagnosticsApi.getReport()
    applyDiagnosticReport(diagnosticReport, message)
  } catch (event) {
    diagnosticReportError = normalizeError(event)
    applyDiagnosticFailure(diagnosticReportError, message)
  }
  const ok = await copyToClipboard(buildErrorDiagnostic({
    scope: 'message',
    message,
    error: message.errorMessage,
    diagnosticReport,
    diagnosticReportError,
  }))
  if (!ok) return
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1600)
}

async function createAndValidateBackup() {
  if (creatingBackup.value) return
  creatingBackup.value = true
  backupNotice.value = null
  backupError.value = null
  try {
    const backup = await diagnosticsApi.createBackup()
    const validation = await diagnosticsApi.validateBackup(backup.fileName)
    if (validation.status === 'OK') {
      backupNotice.value = `已创建并校验本地备份：${backup.fileName}`
    } else {
      backupNotice.value = `已创建本地备份，但校验结果为 ${validation.status}：${validation.detail}`
    }
  } catch (event) {
    backupError.value = normalizeError(event)
  } finally {
    creatingBackup.value = false
  }
}
</script>

<template>
  <div class="flex max-w-full self-end items-start gap-3 rounded-2xl border border-destructive/30 bg-destructive/[0.04] px-4 py-3 text-destructive shadow-sm md:max-w-[85%]">
    <AlertCircle :size="16" class="mt-0.5 shrink-0" />
    <div class="min-w-0 flex-1 space-y-2">
      <div class="space-y-1">
        <p class="text-sm font-medium leading-5">本轮消息发送失败</p>
        <p class="text-sm leading-6 text-destructive/90">{{ message.errorMessage || '发送失败' }}</p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-8 items-center gap-1 rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8"
          @click="emit('retry', message)"
        >
          <RotateCcw :size="12" />
          <span>重试发送</span>
        </button>
        <button
          type="button"
          class="inline-flex h-8 items-center gap-1 rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8"
          :title="copied ? '诊断信息已复制' : '复制诊断信息'"
          @click="copyDiagnostic(message)"
        >
          <Check v-if="copied" :size="12" />
          <Copy v-else :size="12" />
          <span>{{ copied ? '已复制' : '复制诊断' }}</span>
        </button>
        <button
          type="button"
          class="inline-flex h-8 items-center gap-1 rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8 disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="analyzingDiagnostic"
          :title="analyzingDiagnostic ? '正在分析本机状态' : '分析本机状态并给出下一步'"
          @click="analyzeLocalStatus(message)"
        >
          <Loader2 v-if="analyzingDiagnostic" :size="12" class="animate-spin" />
          <Activity v-else :size="12" />
          <span>{{ analyzingDiagnostic ? '分析中' : diagnosticActions.length > 0 || diagnosticError ? '重新分析' : '分析本机' }}</span>
        </button>
        <button
          v-if="backupSuggested || backupNotice || backupError"
          type="button"
          class="inline-flex h-8 items-center gap-1 rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8 disabled:cursor-not-allowed disabled:opacity-60"
          :disabled="creatingBackup"
          :title="creatingBackup ? '正在创建本地备份' : '创建并校验本地备份'"
          @click="createAndValidateBackup"
        >
          <Loader2 v-if="creatingBackup" :size="12" class="animate-spin" />
          <Archive v-else :size="12" />
          <span>{{ creatingBackup ? '备份中' : '创建备份' }}</span>
        </button>
        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="inline-flex h-8 items-center rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8"
        >
          查看任务步骤
        </RouterLink>
      </div>
      <div
        v-if="recoveryBrief"
        class="space-y-1 border-t border-destructive/15 pt-2 text-xs leading-5 text-destructive/80"
        role="status"
      >
        <p class="flex items-center gap-1.5 font-medium text-destructive/90">
          <CornerDownRight :size="12" class="shrink-0" />
          <span>{{ recoveryBrief.title }}</span>
        </p>
        <p v-if="recoveryBrief.detail">{{ recoveryBrief.detail }}</p>
        <ul v-if="recoveryBrief.contextLines.length > 0" class="space-y-1">
          <li
            v-for="line in recoveryBrief.contextLines"
            :key="line"
            class="flex gap-1.5"
          >
            <span aria-hidden="true">-</span>
            <span>{{ line }}</span>
          </li>
        </ul>
        <ul v-if="recoveryBrief.nextActions.length > 0" class="space-y-1">
          <li
            v-for="action in recoveryBrief.nextActions"
            :key="action"
            class="flex gap-1.5"
          >
            <span aria-hidden="true">-</span>
            <span>{{ action }}</span>
          </li>
        </ul>
      </div>
      <div
        v-if="diagnosticActions.length > 0 || diagnosticSummary || diagnosticError || visibleRepairLinks.length > 0"
        class="space-y-1 border-t border-destructive/15 pt-2 text-xs leading-5 text-destructive/80"
        role="status"
      >
        <p v-if="diagnosticSummary">
          本机状态{{ diagnosticStatus ? ` ${diagnosticStatus}` : '' }}：{{ diagnosticSummary }}
        </p>
        <p v-if="diagnosticError" class="text-destructive/90">本机状态分析失败：{{ diagnosticError }}</p>
        <ul v-if="diagnosticActions.length > 0" class="space-y-1">
          <li
            v-for="action in diagnosticActions"
            :key="action"
            class="flex gap-1.5"
          >
            <span aria-hidden="true">-</span>
            <span>{{ action }}</span>
          </li>
        </ul>
        <div v-if="visibleRepairLinks.length > 0" class="flex flex-wrap gap-2 pt-1">
          <RouterLink
            v-for="link in visibleRepairLinks"
            :key="link.id"
            :to="{ name: link.routeName }"
            class="inline-flex h-7 items-center gap-1 rounded-full border border-destructive/20 px-2.5 text-xs font-medium text-destructive/90 transition-colors hover:bg-destructive/8"
            :title="link.title"
          >
            <component :is="link.icon" :size="12" />
            <span>{{ link.label }}</span>
          </RouterLink>
        </div>
      </div>
      <p v-if="backupNotice" class="text-xs leading-5 text-destructive/75">{{ backupNotice }}</p>
      <p v-if="backupError" class="text-xs leading-5 text-destructive/90">创建备份失败：{{ backupError }}</p>
    </div>
  </div>
</template>
