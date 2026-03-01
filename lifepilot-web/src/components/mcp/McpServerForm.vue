<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSkillStore } from '@/stores/skill'
import type { McpServer } from '@/types'

const props = defineProps<{
  server?: McpServer | null
  mode: 'create' | 'edit'
}>()

const emit = defineEmits<{
  close: []
  saved: [server: McpServer]
}>()

const store = useSkillStore()

const formData = ref({
  name: '',
  transport: 'STDIO' as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY',
  command: '',
  args: [] as string[],
  url: '',
  env: {} as Record<string, string>,
  timeout: 30,
  autoConnect: true,
  reconnect: true,
  reconnectDelay: 1000,
  maxReconnectAttempts: 5,
  healthCheckInterval: 60
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})

watch(() => props.server, (server) => {
    if (server?.config) {
      const config = server.config
      formData.value = {
        name: server.name,
        transport: config.transport,
        command: config.command || '',
        args: config.args || [],
        url: config.url || '',
        env: config.env || {},
        // 后端配置中这些字段为数字类型，这里直接使用数值并提供合理默认值
        timeout: typeof config.timeout === 'number' ? config.timeout : 30,
        autoConnect: config.autoConnect ?? true,
        reconnect: config.reconnect ?? true,
        reconnectDelay: typeof config.reconnectDelay === 'number' ? config.reconnectDelay : 1000,
        maxReconnectAttempts: config.maxReconnectAttempts ?? 5,
        healthCheckInterval: typeof config.healthCheckInterval === 'number' ? config.healthCheckInterval : 60
      }
  } else if (props.mode === 'create') {
    formData.value = {
      name: '',
      transport: 'STDIO',
      command: '',
      args: [],
      url: '',
      env: {},
      timeout: 30,
      autoConnect: true,
      reconnect: true,
      reconnectDelay: 1000,
      maxReconnectAttempts: 5,
      healthCheckInterval: 60
    }
  }
}, { immediate: true })

function validate(): boolean {
  errors.value = {}
  
  if (!formData.value.name.trim()) {
    errors.value.name = '名称不能为空'
  }
  
  if (formData.value.transport === 'STDIO' && !formData.value.command.trim()) {
    errors.value.command = '命令不能为空'
  }
  
  if (formData.value.transport !== 'STDIO' && !formData.value.url.trim()) {
    errors.value.url = 'URL 不能为空'
  }
  
  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  
  loading.value = true
  try {
    const data: any = {
      name: formData.value.name,
      transport: formData.value.transport,
      timeout: formData.value.timeout,
      autoConnect: formData.value.autoConnect,
      reconnect: formData.value.reconnect,
      reconnectDelay: formData.value.reconnectDelay,
      maxReconnectAttempts: formData.value.maxReconnectAttempts,
      healthCheckInterval: formData.value.healthCheckInterval
    }
    
    if (formData.value.transport === 'STDIO') {
      data.command = formData.value.command
      if (formData.value.args.length > 0) {
        data.args = formData.value.args
      }
    } else {
      data.url = formData.value.url
    }
    
    if (Object.keys(formData.value.env).length > 0) {
      data.env = formData.value.env
    }
    
    let server: McpServer
    if (props.mode === 'create') {
      server = await store.createMcpServer(data)
    } else {
      server = await store.updateMcpServer(props.server!.name, data)
    }
    
    emit('saved', server)
    emit('close')
  } catch (e) {
    // 错误已在 store 中处理
  } finally {
    loading.value = false
  }
}

function addArg() {
  const arg = prompt('请输入参数:')
  if (arg) {
    formData.value.args.push(arg)
  }
}

function removeArg(index: number) {
  formData.value.args.splice(index, 1)
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-2xl max-h-[90vh] overflow-y-auto m-4">
      <div class="p-6">
        <div class="flex items-center justify-between mb-6">
          <h2 class="text-xl font-semibold text-foreground">
            {{ mode === 'create' ? '新建 MCP Server' : '编辑 MCP Server' }}
          </h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
          >×</button>
        </div>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <!-- 名称 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              名称 <span class="text-destructive">*</span>
            </label>
            <input
              v-model="formData.name"
              type="text"
              :disabled="mode === 'edit'"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              :class="{ 'border-destructive': errors.name }"
            />
            <p v-if="errors.name" class="text-xs text-destructive mt-1">{{ errors.name }}</p>
          </div>

          <!-- 传输类型 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              传输类型 <span class="text-destructive">*</span>
            </label>
            <select
              v-model="formData.transport"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value="STDIO">STDIO</option>
              <option value="STREAMABLE_HTTP">STREAMABLE_HTTP</option>
              <option value="SSE_LEGACY">SSE_LEGACY</option>
            </select>
          </div>

          <!-- STDIO 配置 -->
          <template v-if="formData.transport === 'STDIO'">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                命令 <span class="text-destructive">*</span>
              </label>
              <input
                v-model="formData.command"
                type="text"
                class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                :class="{ 'border-destructive': errors.command }"
                placeholder="例如: node"
              />
              <p v-if="errors.command" class="text-xs text-destructive mt-1">{{ errors.command }}</p>
            </div>

            <div>
              <label class="block text-sm font-medium text-foreground mb-1">参数</label>
              <div class="flex flex-wrap gap-2 mb-2">
                <span
                  v-for="(arg, index) in formData.args"
                  :key="index"
                  class="inline-flex items-center gap-1 px-2 py-1 bg-accent text-accent-foreground rounded-md text-sm"
                >
                  {{ arg }}
                  <button
                    type="button"
                    class="hover:text-destructive"
                    @click="removeArg(index)"
                  >×</button>
                </span>
              </div>
              <button
                type="button"
                class="text-sm px-3 py-1 border border-input rounded-md hover:bg-accent transition-colors"
                @click="addArg"
              >+ 添加参数</button>
            </div>
          </template>

          <!-- HTTP 配置 -->
          <template v-else>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                URL <span class="text-destructive">*</span>
              </label>
              <input
                v-model="formData.url"
                type="text"
                class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                :class="{ 'border-destructive': errors.url }"
                placeholder="例如: http://localhost:3000"
              />
              <p v-if="errors.url" class="text-xs text-destructive mt-1">{{ errors.url }}</p>
            </div>
          </template>

          <!-- 超时 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">超时（秒）</label>
            <input
              v-model.number="formData.timeout"
              type="number"
              min="1"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>

          <!-- 自动连接 -->
          <div class="flex items-center gap-2">
            <input
              v-model="formData.autoConnect"
              type="checkbox"
              id="autoConnect"
              class="w-4 h-4"
            />
            <label for="autoConnect" class="text-sm text-foreground">自动连接</label>
          </div>

          <!-- 自动重连 -->
          <div class="flex items-center gap-2">
            <input
              v-model="formData.reconnect"
              type="checkbox"
              id="reconnect"
              class="w-4 h-4"
            />
            <label for="reconnect" class="text-sm text-foreground">自动重连</label>
          </div>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-2 pt-4 border-t border-border">
            <button
              type="button"
              class="px-4 py-2 text-sm border border-input rounded-md hover:bg-accent transition-colors"
              @click="emit('close')"
            >取消</button>
            <button
              type="submit"
              :disabled="loading"
              class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50"
            >{{ loading ? '保存中...' : '保存' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
