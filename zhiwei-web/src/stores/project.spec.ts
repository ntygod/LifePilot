import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ProjectDto } from '@/api/project'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

import * as projectApi from '@/api/project'
import { useProjectStore } from './project'

const projectApiMock = vi.mocked(projectApi, { deep: true })

function makeProject(id: string, overrides: Partial<ProjectDto> = {}): ProjectDto {
  return {
    id,
    name: `项目-${id}`,
    instructions: '',
    isolation: 'ISOLATED',
    memorySpaceId: `space-${id}`,
    createdAt: '2026-04-23T00:00:00Z',
    updatedAt: '2026-04-23T00:00:00Z',
    ...overrides,
  }
}

describe('useProjectStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchProjects 拉取并保存列表', async () => {
    const items = [makeProject('p1'), makeProject('p2')]
    projectApiMock.listProjects.mockResolvedValueOnce(items)

    const store = useProjectStore()
    const result = await store.fetchProjects()

    expect(projectApiMock.listProjects).toHaveBeenCalledTimes(1)
    expect(result).toEqual(items)
    expect(store.projects).toEqual(items)
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('loading 状态在请求前中后正确切换', async () => {
    let resolveFn: ((value: ProjectDto[]) => void) | undefined
    const pending = new Promise<ProjectDto[]>(resolve => {
      resolveFn = resolve
    })
    projectApiMock.listProjects.mockReturnValueOnce(pending)

    const store = useProjectStore()
    expect(store.loading).toBe(false)

    const inflight = store.fetchProjects()
    expect(store.loading).toBe(true)

    resolveFn!([makeProject('p1')])
    await inflight

    expect(store.loading).toBe(false)
  })

  it('createProject 成功后追加到列表开头', async () => {
    const existing = makeProject('p1')
    projectApiMock.listProjects.mockResolvedValueOnce([existing])
    const created = makeProject('p-new', { name: '新项目' })
    projectApiMock.createProject.mockResolvedValueOnce(created)

    const store = useProjectStore()
    await store.fetchProjects()
    const result = await store.createProject({ name: '新项目' })

    expect(projectApiMock.createProject).toHaveBeenCalledWith({ name: '新项目' })
    expect(result).toEqual(created)
    expect(store.projects).toEqual([created, existing])
  })

  it('updateProject 成功后更新列表对应项', async () => {
    const before = makeProject('p1', { name: '原名' })
    projectApiMock.listProjects.mockResolvedValueOnce([before, makeProject('p2')])
    const after = makeProject('p1', { name: '改名', instructions: '新指令' })
    projectApiMock.updateProject.mockResolvedValueOnce(after)

    const store = useProjectStore()
    await store.fetchProjects()
    const result = await store.updateProject('p1', { name: '改名', instructions: '新指令' })

    expect(projectApiMock.updateProject).toHaveBeenCalledWith('p1', {
      name: '改名',
      instructions: '新指令',
    })
    expect(result).toEqual(after)
    expect(store.projects[0]).toEqual(after)
    expect(store.projects[1].id).toBe('p2')
  })

  it('deleteProject 成功后从列表移除', async () => {
    projectApiMock.listProjects.mockResolvedValueOnce([
      makeProject('p1'),
      makeProject('p2'),
      makeProject('p3'),
    ])
    projectApiMock.deleteProject.mockResolvedValueOnce(undefined)

    const store = useProjectStore()
    await store.fetchProjects()
    await store.deleteProject('p2')

    expect(projectApiMock.deleteProject).toHaveBeenCalledWith('p2')
    expect(store.projects.map(p => p.id)).toEqual(['p1', 'p3'])
  })

  it('请求失败时写入 error 并 rethrow', async () => {
    const err = { code: 500, message: '服务端炸了', timestamp: '2026-04-23T00:00:00Z' }
    projectApiMock.createProject.mockRejectedValueOnce(err)

    const store = useProjectStore()

    await expect(store.createProject({ name: '失败项目' })).rejects.toEqual(err)
    expect(store.error).toBe('服务端炸了')
    expect(store.projects).toEqual([])
  })
})
