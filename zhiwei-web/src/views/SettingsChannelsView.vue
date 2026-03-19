<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { settingsApi } from '@/api/client'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'

const loading = ref(true)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

const feishu = reactive({
  enabled: false,
  appId: '',
  appSecret: '',
  verificationToken: '',
  encryptKey: '',
})

const wecom = reactive({
  enabled: false,
  corpId: '',
  agentId: '',
  secret: '',
  token: '',
  encodingAesKey: '',
})

const dingtalk = reactive({
  enabled: false,
  appKey: '',
  appSecret: '',
  robotCode: '',
})

onMounted(() => loadConfig())

async function loadConfig() {
  loading.value = true
  loadError.value = null
  try {
    const data = await settingsApi.getChannelConfig()
    if (data.feishu) Object.assign(feishu, data.feishu)
    if (data.wecom) Object.assign(wecom, data.wecom)
    if (data.dingtalk) Object.assign(dingtalk, data.dingtalk)
  } catch (e) {
    console.error('加载渠道配置失败:', e)
    loadError.value = '暂时无法读取渠道配置，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  try {
    const data = await settingsApi.updateChannelConfig({
      feishu: { ...feishu },
      wecom: { ...wecom },
      dingtalk: { ...dingtalk },
    })
    if (data.feishu) Object.assign(feishu, data.feishu)
    if (data.wecom) Object.assign(wecom, data.wecom)
    if (data.dingtalk) Object.assign(dingtalk, data.dingtalk)
    saveSuccess.value = true
    window.setTimeout(() => { saveSuccess.value = false }, 2200)
  } catch (e: any) {
    console.error('保存渠道配置失败:', e)
    saveError.value = e?.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <!-- 加载骨架 -->
    <div v-if="loading" class="space-y-6">
      <Skeleton class="h-8 w-48" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
    </div>

    <!-- 加载失败 -->
    <div v-else-if="loadError" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ loadError }}</p>
      <Button variant="outline" class="mt-4" @click="loadConfig">重试</Button>
    </div>

    <!-- 表单 -->
    <form v-else class="space-y-8" @submit.prevent="handleSave">

      <!-- 提示信息 -->
      <div class="detail-card border-emerald-500/30 bg-emerald-500/5 px-5 py-4">
        <p class="text-sm leading-6 text-muted-foreground">
          渠道开关和凭证保存后即时生效，无需重启服务。
        </p>
      </div>

      <!-- 飞书 -->
      <SettingSection title="飞书" icon="🐦" description="飞书机器人事件订阅凭证，用于接收和回复飞书消息。">
        <SettingItem label="启用飞书通道" description="开启后将注册飞书 Webhook 端点，接收和回复飞书消息。">
          <Switch v-model="feishu.enabled" />
        </SettingItem>
        <SettingItem label="App ID" description="飞书开放平台应用的 App ID。">
          <Input v-model="feishu.appId" placeholder="cli_xxxxxxxxxx" class="w-64" />
        </SettingItem>
        <SettingItem label="App Secret" description="飞书应用密钥，用于获取 tenant_access_token。">
          <Input v-model="feishu.appSecret" type="password" placeholder="输入 App Secret" class="w-64" />
        </SettingItem>
        <SettingItem label="Verification Token" description="事件订阅验证 Token。">
          <Input v-model="feishu.verificationToken" type="password" placeholder="输入 Verification Token" class="w-64" />
        </SettingItem>
        <SettingItem label="Encrypt Key" description="事件加密密钥（AES-256-CBC）。">
          <Input v-model="feishu.encryptKey" type="password" placeholder="输入 Encrypt Key" class="w-64" />
        </SettingItem>
      </SettingSection>

      <!-- 企业微信 -->
      <SettingSection title="企业微信" icon="💬" description="企业微信自建应用凭证，用于接收和回复企微消息。">
        <SettingItem label="启用企微通道" description="开启后将注册企微 Webhook 端点，接收和回复企微消息。">
          <Switch v-model="wecom.enabled" />
        </SettingItem>
        <SettingItem label="Corp ID" description="企业 ID。">
          <Input v-model="wecom.corpId" placeholder="wxxxxxxxxxxxxxxxxx" class="w-64" />
        </SettingItem>
        <SettingItem label="Agent ID" description="自建应用的 AgentId。">
          <Input v-model="wecom.agentId" placeholder="1000002" class="w-64" />
        </SettingItem>
        <SettingItem label="Secret" description="自建应用的 Secret，用于获取 access_token。">
          <Input v-model="wecom.secret" type="password" placeholder="输入 Secret" class="w-64" />
        </SettingItem>
        <SettingItem label="Token" description="回调 URL 验证 Token。">
          <Input v-model="wecom.token" type="password" placeholder="输入 Token" class="w-64" />
        </SettingItem>
        <SettingItem label="EncodingAESKey" description="回调消息加密密钥（43 字符）。">
          <Input v-model="wecom.encodingAesKey" type="password" placeholder="输入 EncodingAESKey" class="w-64" />
        </SettingItem>
      </SettingSection>

      <!-- 钉钉 -->
      <SettingSection title="钉钉" icon="🔔" description="钉钉企业内部机器人凭证，用于接收和回复钉钉消息。">
        <SettingItem label="启用钉钉通道" description="开启后将注册钉钉 Webhook 端点，接收和回复钉钉消息。">
          <Switch v-model="dingtalk.enabled" />
        </SettingItem>
        <SettingItem label="App Key" description="钉钉应用的 AppKey。">
          <Input v-model="dingtalk.appKey" placeholder="dingxxxxxxxxxx" class="w-64" />
        </SettingItem>
        <SettingItem label="App Secret" description="钉钉应用密钥，用于签名验证和获取 access_token。">
          <Input v-model="dingtalk.appSecret" type="password" placeholder="输入 App Secret" class="w-64" />
        </SettingItem>
        <SettingItem label="Robot Code" description="机器人编码，用于主动推送消息。">
          <Input v-model="dingtalk.robotCode" placeholder="dingxxxxxxxxxx" class="w-64" />
        </SettingItem>
      </SettingSection>

      <!-- 保存栏 -->
      <div class="sticky bottom-0 z-10 pb-2 pt-4">
        <div class="detail-card bg-background/92 px-4 py-4 backdrop-blur">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="space-y-1">
              <div class="text-sm font-medium text-foreground">保存渠道配置</div>
              <p class="text-sm leading-6 text-muted-foreground">
                保存后立即生效，无需重启。
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <span v-if="saveSuccess" class="text-sm text-emerald-600 dark:text-emerald-400">已保存</span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
              <Button type="submit" :disabled="saving">
                {{ saving ? '保存中...' : '保存渠道配置' }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </form>
  </div>
</template>
