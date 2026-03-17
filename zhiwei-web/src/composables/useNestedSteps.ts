import type { StepModel, StepType } from './useWorkflowModel'

/**
 * 畫布節點的分支歸屬
 * - 'root': 頂層步驟（直接在工作流 steps 列表中）
 * - 'then': 條件分支的 thenSteps
 * - 'else': 條件分支的 elseSteps
 * - 'loop': 循環步驟的 body
 * - 'branch-N': 並行步驟的第 N 個分支
 */
export type BranchType = 'root' | 'then' | 'else' | 'loop' | `branch-${number}`

/**
 * 畫布節點（扁平化後的步驟表示）
 */
export interface CanvasNode {
  /** 畫布上的唯一 ID（可能與原始 step.id 不同） */
  canvasId: string
  /** 原始步驟 ID */
  stepId: string
  /** 步驟名稱 */
  name: string
  /** 步驟類型 */
  type: StepType
  /** 歸屬的分支 */
  branch: BranchType
  /** 父級步驟 ID（如果是分支內的步驟） */
  parentStepId: string | null
  /** 依賴於哪些畫布節點（扁平化後的 ID） */
  dependsOn: string[]
  /** 原始步驟對象的引用（用於配置編輯） */
  rawStep: StepModel
}

/**
 * 將嵌套的 steps 轉為扁平的畫布節點列表。
 *
 * 處理以下類型的嵌套：
 * - condition: thenSteps, elseSteps
 * - loop: body
 * - parallel: branches[]
 *
 * 分支內的步驟按順序串聯：第一步依賴父節點，後續步驟依賴前一步。
 * 這確保了分支內的步驟在畫布上按正確的垂直順序排列。
 */
export function flattenNestedSteps(steps: StepModel[]): CanvasNode[] {
  const canvasNodes: CanvasNode[] = []
  // 頂層步驟 ID 集合，用於判斷外部依賴
  const topLevelIds = new Set(steps.map(s => s.id))

  /**
   * 遞迴處理步驟列表。
   * @param stepList 步驟列表（可能是頂層、分支內、循環體內）
   * @param branch 分支歸屬
   * @param parentCanvasId 父節點的畫布 ID
   * @param isSequential 是否按順序串聯（分支/循環體內為 true）
   */
  function processStepList(
    stepList: StepModel[],
    branch: BranchType,
    parentCanvasId: string | null,
    isSequential: boolean,
  ) {
    let prevCanvasId: string | null = null

    for (const step of stepList) {
      const canvasId = branch === 'root' ? step.id : `${parentCanvasId}-${branch}-${step.id}`

      // 計算依賴
      const currentDependsOn: string[] = []

      if (isSequential) {
        if (prevCanvasId) {
          // 非第一步：依賴前一步（串聯）
          currentDependsOn.push(prevCanvasId)
        } else if (parentCanvasId) {
          // 第一步：依賴父節點
          currentDependsOn.push(parentCanvasId)
        }
      }

      // 頂層步驟保留原始的外部依賴
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

      // 遞迴處理嵌套的步驟
      // 兼容两种数据来源：
      //   1. 编辑器 StepModel：嵌套步骤在 step.config 下（如 config.body, config.thenSteps）
      //   2. 后端 API 原始 JSON：嵌套步骤直接在 step 上（如 step.body, step.thenSteps）
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

  // 頂層步驟不串聯（由 dependsOn 控制）
  processStepList(steps, 'root', null, false)

  return canvasNodes
}

/**
 * 獲取條件分支節點的出口錨點信息
 */
export interface ConditionExits {
  then: string | null  // Then 分支第一個節點的 canvasId
  else: string | null  // Else 分支第一個節點的 canvasId
}

/**
 * 根據畫布節點列表，獲取每個條件節點的 Then/Else 出口連接目標
 */
export function getConditionExits(canvasNodes: CanvasNode[]): Map<string, ConditionExits> {
  const exitsMap = new Map<string, ConditionExits>()

  for (const node of canvasNodes) {
    if (node.type === 'condition') {
      // 查找 then 分支的第一個節點
      const thenNode = canvasNodes.find(n => n.parentStepId === node.canvasId && n.branch === 'then')
      // 查找 else 分支的第一個節點
      const elseNode = canvasNodes.find(n => n.parentStepId === node.canvasId && n.branch === 'else')

      exitsMap.set(node.canvasId, {
        then: thenNode?.canvasId ?? null,
        else: elseNode?.canvasId ?? null,
      })
    }
  }

  return exitsMap
}


/**
 * 扩展 completedStepIds：当父步骤（loop/condition/parallel）已完成时，
 * 自动将其所有子步骤也视为已完成。
 *
 * 解决后端 completedStepIds 只包含顶层步骤 ID，
 * 导致执行视图中嵌套步骤永远显示为 waiting 的问题。
 */
export function expandCompletedStepIds(
  canvasNodes: CanvasNode[],
  completedStepIds: string[],
): Set<string> {
  const completed = new Set(completedStepIds)

  // 收集所有已完成的父节点的 canvasId
  const completedCanvasIds = new Set<string>()
  for (const node of canvasNodes) {
    if (completed.has(node.stepId)) {
      completedCanvasIds.add(node.canvasId)
    }
  }

  // 遍历所有节点，如果父节点已完成，则子节点也标记为已完成
  let changed = true
  while (changed) {
    changed = false
    for (const node of canvasNodes) {
      if (completedCanvasIds.has(node.canvasId)) continue
      if (node.parentStepId && completedCanvasIds.has(node.parentStepId)) {
        completedCanvasIds.add(node.canvasId)
        completed.add(node.stepId)
        changed = true
      }
    }
  }

  return completed
}

/**
 * 计算扁平化后的步骤总数（包含嵌套步骤）。
 */
export function countFlattenedSteps(steps: unknown[]): number {
  return flattenNestedSteps(steps as StepModel[]).length
}
