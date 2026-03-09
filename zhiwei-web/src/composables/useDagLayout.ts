import type { StepModel } from './useWorkflowModel'

/**
 * DAG 拓扑排序与环路检测 composable。
 *
 * 提供两个纯函数：
 * - computeLayers: Kahn 拓扑排序分层
 * - wouldCreateCycle: DFS 环路检测
 */
export function useDagLayout(): {
  /** Kahn 拓扑排序分层，返回 string[][]（每层的步骤 ID 列表） */
  computeLayers: (steps: StepModel[]) => string[][]
  /** DFS 环路检测，返回 true 表示添加 edge 后会产生环路 */
  wouldCreateCycle: (steps: StepModel[], fromId: string, toId: string) => boolean
} {
  /**
   * Kahn 拓扑排序分层算法。
   *
   * 1. 构建入度表和邻接表（边方向：依赖 → 被依赖者）
   * 2. 入度为 0 的步骤作为第 0 层
   * 3. 逐层处理，每层节点的后继入度减 1，入度归零则进入下一层
   * 4. 返回分层结果（每层为步骤 ID 数组）
   */
  function computeLayers(steps: StepModel[]): string[][] {
    // 构建入度表和邻接表
    const inDegree = new Map<string, number>()
    const adj = new Map<string, string[]>()

    for (const step of steps) {
      inDegree.set(step.id, step.dependsOn.length)
      for (const dep of step.dependsOn) {
        if (!adj.has(dep)) adj.set(dep, [])
        adj.get(dep)!.push(step.id)
      }
    }

    const layers: string[][] = []
    let queue = steps.filter(s => s.dependsOn.length === 0).map(s => s.id)

    while (queue.length > 0) {
      layers.push([...queue])
      const nextQueue: string[] = []
      for (const id of queue) {
        for (const next of (adj.get(id) ?? [])) {
          inDegree.set(next, inDegree.get(next)! - 1)
          if (inDegree.get(next) === 0) nextQueue.push(next)
        }
      }
      queue = nextQueue
    }

    return layers
  }

  /**
   * 环路检测：判断添加 fromId → toId 的边后是否会产生环路。
   *
   * 语义：toId 依赖 fromId（fromId 加入 toId.dependsOn）。
   * 等价于检查当前图中是否存在从 toId 到 fromId 的路径。
   * 如果存在，添加 fromId → toId 会形成环路。
   *
   * 使用 BFS 从 toId 出发，沿邻接表（依赖 → 被依赖者方向）搜索是否可达 fromId。
   */
  function wouldCreateCycle(steps: StepModel[], fromId: string, toId: string): boolean {
    // 自环检测
    if (fromId === toId) return true

    // 构建邻接表：边方向为 依赖 → 被依赖者（即 dep → step.id）
    const adj = new Map<string, string[]>()
    for (const step of steps) {
      for (const dep of step.dependsOn) {
        if (!adj.has(dep)) adj.set(dep, [])
        adj.get(dep)!.push(step.id)
      }
    }

    // BFS 从 toId 出发，检查是否可达 fromId
    const visited = new Set<string>()
    const queue: string[] = [toId]
    visited.add(toId)

    while (queue.length > 0) {
      const current = queue.shift()!
      for (const next of (adj.get(current) ?? [])) {
        if (next === fromId) return true
        if (!visited.has(next)) {
          visited.add(next)
          queue.push(next)
        }
      }
    }

    return false
  }

  return { computeLayers, wouldCreateCycle }
}
