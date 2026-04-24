import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ScheduledTaskDto } from '@/api/scheduledTask'

vi.mock('@/api/scheduledTask', () => ({
  listScheduledTasks: vi.fn(),
  updateScheduledTask: vi.fn(),
  deleteScheduledTask: vi.fn(),
}))

import * as scheduledTaskApi from '@/api/scheduledTask'
import { useScheduledTaskStore } from './scheduledTask'

const apiMock = vi.mocked(scheduledTaskApi, { deep: true })

function makeTask(
  id: string,
  overrides: Partial<ScheduledTaskDto> = {},
): ScheduledTaskDto {
  return {
    id,
    name: `任务-${id}`,
    schedule: '0 0 9 * * *',
    instruction: '示例指令',
    status: 'active',
    skillIds: null,
    projectId: null,
    createdAt: '2026-04-23T00:00:00Z',
    updatedAt: '2026-04-23T00:00:00Z',
    nextExecutionAt: null,
    ...overrides,
  }
}

describe('useScheduledTaskStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchAll 拉取并保存列表', async () => {
    const items = [makeTask('t1', { projectId: 'p-1' }), makeTask('t2')]
    apiMock.listScheduledTasks.mockResolvedValueOnce(items)

    const store = useScheduledTaskStore()
    const result = await store.fetchAll()

    expect(apiMock.listScheduledTasks).toHaveBeenCalledWith(null)
    expect(result).toEqual(items)
    expect(store.tasks).toEqual(items)
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('fetchAll 按 projectId 过滤时透传给 API', async () => {
    apiMock.listScheduledTasks.mockResolvedValueOnce([makeTask('t1', { projectId: 'p-42' })])

    const store = useScheduledTaskStore()
    await store.fetchAll('p-42')

    expect(apiMock.listScheduledTasks).toHaveBeenCalledWith('p-42')
  })

  it('loading 状态在请求前中后正确切换', async () => {
    let resolveFn: ((value: ScheduledTaskDto[]) => void) | undefined
    const pending = new Promise<ScheduledTaskDto[]>(resolve => {
      resolveFn = resolve
    })
    apiMock.listScheduledTasks.mockReturnValueOnce(pending)

    const store = useScheduledTaskStore()
    expect(store.loading).toBe(false)

    const inflight = store.fetchAll()
    expect(store.loading).toBe(true)

    resolveFn!([makeTask('t1')])
    await inflight

    expect(store.loading).toBe(false)
  })

  it('pauseTask 把 status 改为 paused 并更新列表', async () => {
    apiMock.listScheduledTasks.mockResolvedValueOnce([makeTask('t1'), makeTask('t2')])
    const paused = makeTask('t1', { status: 'paused' })
    apiMock.updateScheduledTask.mockResolvedValueOnce(paused)

    const store = useScheduledTaskStore()
    await store.fetchAll()
    const result = await store.pauseTask('t1')

    expect(apiMock.updateScheduledTask).toHaveBeenCalledWith('t1', { status: 'paused' })
    expect(result).toEqual(paused)
    expect(store.tasks[0].status).toBe('paused')
    expect(store.tasks[1].id).toBe('t2')
  })

  it('resumeTask 把 paused 改回 active', async () => {
    apiMock.listScheduledTasks.mockResolvedValueOnce([makeTask('t1', { status: 'paused' })])
    const active = makeTask('t1', { status: 'active' })
    apiMock.updateScheduledTask.mockResolvedValueOnce(active)

    const store = useScheduledTaskStore()
    await store.fetchAll()
    const result = await store.resumeTask('t1')

    expect(apiMock.updateScheduledTask).toHaveBeenCalledWith('t1', { status: 'active' })
    expect(result).toEqual(active)
    expect(store.tasks[0].status).toBe('active')
  })

  it('updateTask 更新列表对应项（多字段）', async () => {
    apiMock.listScheduledTasks.mockResolvedValueOnce([
      makeTask('t1', { name: '原名' }),
      makeTask('t2'),
    ])
    const updated = makeTask('t1', { name: '改名', schedule: '0 0 10 * * *' })
    apiMock.updateScheduledTask.mockResolvedValueOnce(updated)

    const store = useScheduledTaskStore()
    await store.fetchAll()
    const result = await store.updateTask('t1', { name: '改名', schedule: '0 0 10 * * *' })

    expect(result).toEqual(updated)
    expect(store.tasks[0]).toEqual(updated)
    expect(store.tasks[1].id).toBe('t2')
  })

  it('deleteTask 成功后从列表移除', async () => {
    apiMock.listScheduledTasks.mockResolvedValueOnce([
      makeTask('t1'),
      makeTask('t2'),
      makeTask('t3'),
    ])
    apiMock.deleteScheduledTask.mockResolvedValueOnce(undefined)

    const store = useScheduledTaskStore()
    await store.fetchAll()
    await store.deleteTask('t2')

    expect(apiMock.deleteScheduledTask).toHaveBeenCalledWith('t2')
    expect(store.tasks.map(t => t.id)).toEqual(['t1', 't3'])
  })

  it('请求失败时写入 error 并 rethrow', async () => {
    const err = { code: 500, message: '网络错误', timestamp: '2026-04-23T00:00:00Z' }
    apiMock.listScheduledTasks.mockRejectedValueOnce(err)

    const store = useScheduledTaskStore()

    await expect(store.fetchAll()).rejects.toEqual(err)
    expect(store.error).toBe('网络错误')
    expect(store.tasks).toEqual([])
    expect(store.loading).toBe(false)
  })
})
