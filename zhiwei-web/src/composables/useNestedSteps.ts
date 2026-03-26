import type { StepModel, StepType } from './useWorkflowModel'

export type BranchType = 'root' | 'then' | 'else' | 'loop' | `branch-${number}`

export interface CanvasNode {
  canvasId: string
  stepId: string
  name: string
  type: StepType
  branch: BranchType
  parentStepId: string | null
  dependsOn: string[]
  rawStep: StepModel
}

/**
 * 将嵌套步骤展开成画布节点列表。
 *
 * 支持：
 * - `condition`: `thenSteps`、`elseSteps`
 * - `loop`: `body`
 * - `parallel`: `branches`
 */
export function flattenNestedSteps(steps: StepModel[]): CanvasNode[] {
  const canvasNodes: CanvasNode[] = []
  const topLevelIds = new Set(steps.map(step => step.id))

  function processStepList(
    stepList: StepModel[],
    branch: BranchType,
    parentCanvasId: string | null,
    isSequential: boolean,
  ) {
    let prevCanvasId: string | null = null

    for (const step of stepList) {
      const canvasId = branch === 'root' ? step.id : `${parentCanvasId}-${branch}-${step.id}`
      const currentDependsOn: string[] = []

      if (isSequential) {
        if (prevCanvasId) {
          currentDependsOn.push(prevCanvasId)
        } else if (parentCanvasId) {
          currentDependsOn.push(parentCanvasId)
        }
      }

      if (branch === 'root') {
        for (const depId of step.dependsOn) {
          if (topLevelIds.has(depId) && !currentDependsOn.includes(depId)) {
            currentDependsOn.push(depId)
          }
        }
      }

      const node: CanvasNode = {
        canvasId,
        stepId: step.id,
        name: step.name,
        type: step.type,
        branch,
        parentStepId: parentCanvasId,
        dependsOn: currentDependsOn,
        rawStep: step,
      }

      canvasNodes.push(node)

      // 兼容编辑器 StepModel 与后端原始 JSON 两种嵌套来源。
      const raw = step as any
      const config = raw.config as any

      if (step.type === 'condition') {
        const thenSteps = config?.thenSteps ?? raw.thenSteps
        const elseSteps = config?.elseSteps ?? raw.elseSteps
        if (thenSteps && Array.isArray(thenSteps)) {
          processStepList(thenSteps, 'then', canvasId, true)
        }
        if (elseSteps && Array.isArray(elseSteps)) {
          processStepList(elseSteps, 'else', canvasId, true)
        }
      }

      if (step.type === 'loop') {
        const body = config?.body ?? raw.body
        if (body && Array.isArray(body)) {
          processStepList(body, 'loop', canvasId, true)
        }
      }

      if (step.type === 'parallel') {
        const branches = config?.branches ?? raw.branches
        if (branches && Array.isArray(branches)) {
          branches.forEach((branchSteps: StepModel[], branchIndex: number) => {
            const branchType = `branch-${branchIndex}` as BranchType
            processStepList(branchSteps, branchType, canvasId, true)
          })
        }
      }

      prevCanvasId = canvasId
    }
  }

  processStepList(steps, 'root', null, false)
  return canvasNodes
}

export interface ConditionExits {
  then: string | null
  else: string | null
}

export function getConditionExits(canvasNodes: CanvasNode[]): Map<string, ConditionExits> {
  const exitsMap = new Map<string, ConditionExits>()

  for (const node of canvasNodes) {
    if (node.type === 'condition') {
      const thenNode = canvasNodes.find(item => item.parentStepId === node.canvasId && item.branch === 'then')
      const elseNode = canvasNodes.find(item => item.parentStepId === node.canvasId && item.branch === 'else')

      exitsMap.set(node.canvasId, {
        then: thenNode?.canvasId ?? null,
        else: elseNode?.canvasId ?? null,
      })
    }
  }

  return exitsMap
}

/**
 * 当前端只收到父步骤完成状态时，将其所有子步骤也视为完成。
 */
export function expandCompletedStepIds(
  canvasNodes: CanvasNode[],
  completedStepIds: string[],
): Set<string> {
  const completed = new Set(completedStepIds)
  const completedCanvasIds = new Set<string>()

  for (const node of canvasNodes) {
    if (completed.has(node.stepId)) {
      completedCanvasIds.add(node.canvasId)
    }
  }

  let changed = true
  while (changed) {
    changed = false
    for (const node of canvasNodes) {
      if (completedCanvasIds.has(node.canvasId)) {
        continue
      }
      if (node.parentStepId && completedCanvasIds.has(node.parentStepId)) {
        completedCanvasIds.add(node.canvasId)
        completed.add(node.stepId)
        changed = true
      }
    }
  }

  return completed
}

export function countFlattenedSteps(steps: unknown[]): number {
  return flattenNestedSteps(steps as StepModel[]).length
}
