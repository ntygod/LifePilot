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
      const config = step.config as any

      if (step.type === 'condition' && config) {
        if (config.thenSteps && Array.isArray(config.thenSteps)) {
          processStepList(config.thenSteps, 'then', canvasId, true)
        }
        if (config.elseSteps && Array.isArray(config.elseSteps)) {
          processStepList(config.elseSteps, 'else', canvasId, true)
        }
      }

      if (step.type === 'loop' && config) {
        if (config.body && Array.isArray(config.body)) {
          processStepList(config.body, 'loop', canvasId, true)
        }
      }

      if (step.type === 'parallel' && config) {
        if (config.branches && Array.isArray(config.branches)) {
          config.branches.forEach((branchSteps: StepModel[], branchIndex: number) => {
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
