// ZhiWei 閸撳秶顏猾璇茬€风€规矮绠?
/** 娴兼俺鐦介幗妯款洣 */
export interface ChatSession {
  id: string
  title: string
  createdAt: string   // ISO 8601
  updatedAt: string
  /** 閺勵垰鎯佺純顕€銆?*/
  pinned?: boolean
  /** 閺勵垰鎯佽ぐ鎺撱€?*/
  archived?: boolean
  /** 閺堚偓鏉╂垳绔撮弶鈩冪Х閹垱鎲崇憰渚婄礄閹搭亝鏌囬弰鍓с仛閿?*/
  lastMessagePreview?: string
  /** 娴兼俺鐦界猾璇茬€烽弽鍥唶閿涘牆褰查柅澶涚礆 */
  type?: string
}

export interface ChatSessionDetail extends ChatSession {
  knowledgeBaseIds: string[]
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  maxSteps?: number
  maxDurationSeconds?: number
  messageCount: number
  totalTokens: number
}

/** 閼卞﹤銇夊☉鍫熶紖闂勫嫪娆㈤敍鍫濆缁旑垰鐫嶇粈铏规暏閿?*/
export interface ChatAttachment {
  /** 閸氬海顏潻鏂挎礀閻ㄥ嫭鏋冩禒?ID閿涘牏鏁ゆ禍搴℃倵缂侇厼顦垮Ο鈩冣偓浣界熅閻㈠彉绗屽Λ鈧槐顫礆 */
  fileId: string
  /** 閸欘垵顔栭梻顔炬畱閺傚洣娆?URL閿涘牓鈧艾鐖舵稉鍝勬倵缁旑垱褰佹笟娑氭畱閻╃顕捄顖氱窞閿涘瞼绮＄純鎴濆彠 / CDN 娴狅絿鎮婇敍?*/
  url: string
  /** 閸樼喎顫愰弬鍥︽閸?*/
  filename: string
  /** 閺傚洣娆㈡径褍鐨敍鍫濈摟閼哄偊绱?*/
  size: number
  /** MIME 缁鐎?*/
  type: string
  /** 閺勵垰鎯佹稉鍝勬禈閻楀洨琚崹瀣剁礉娓氬じ绨崜宥囶伂閹稿娴橀悧鍥ㄧ壉瀵繑瑕嗛弻鎾剁級閻ｃ儱娴?*/
  isImage: boolean
}

export type CompletionMode = 'NORMAL' | 'DEGRADED' | 'SUSPENDED'

export type ResumePolicy = 'AUTO' | 'FRESH'

/** 濞戝牊浼?*/
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'tool-confirmation'
  content: string
  a2uiComponents?: A2uiComponent[]
  timestamp: number
  /**
   * 閺堫剚娼☉鍫熶紖鐎电懓绨查惃鍕鏉烆喗甯归悶鍡橆洤鐟曚焦鎲崇憰浣碘偓?   * 閻㈠崬鎮楃粩顖氭躬 SSE DONE 娴滃娆㈡稉顓⑩偓姘崇箖 reasoningSummary 鐎涙顔屾潻鏂挎礀閵?   */
  reasoningSummary?: string
  /** 閺堫剚娼☉鍫熶紖鐎电懓绨查惃鍕腹閻炲棔绨ㄦ禒鑸垫闂傚鍤庨敍鍫㈡暠閸撳秶顏崷?SSE 濞翠胶绮ㄩ弶鐔告娴?useChat 韫囶偆鍙庢穱婵嗙摠閿?*/
  reasoningEvents?: ReasoningEvent[]
  /** 閸撳秶顏笟褏娈戦崣鎴︹偓?/ 婢跺嫮鎮婇悩鑸碘偓浣圭垼鐠佸府绱濋悽銊ょ艾鐏炴洜銇?閸欐垿鈧椒鑵?/ 婢惰精瑙?/ 閸欘垶鍣哥拠? */
  status?: 'pending' | 'success' | 'error'
  /** 娑撳孩婀伴弶鈩冪Х閹垳娴夐崗宕囨畱闁挎瑨顕ょ拠瀛樻閿涘牅绮庨崷?status === 'error' 閺冭泛鐫嶇粈鐚寸礆 */
  errorMessage?: string
  /** 閸氬海顏幍褑顢戞潪銊ㄦ姉 ID閿涘牆顩х€涙ê婀敍澶涚礉閻劋绨捄瀹犳祮閸?Trace 鐠囷附鍎?*/
  traceId?: string
  /** 閺堫剝鐤嗙€瑰本鍨氶幀?*/
  completionMode?: CompletionMode
  /** 閺傤厾鍋ｉ幁銏狀槻閺夈儲绨?traceId */
  resumedFromTraceId?: string
  /** 妤傛ü瀵掗崥搴ｆ畱 HTML 閸愬懎顔愰敍鍫㈡暏娴滃孩鎮崇槐銏ょ彯娴滎噯绱?*/
  highlightedContent?: string
  /** 闂勫嫪娆㈤崚妤勩€冮敍鍫濇禈閻?閺傚洣娆㈢粵澶涚礆閿涘瞼鏁ゆ禍搴″缁旑垰鐫嶇粈铏圭級閻ｃ儱娴樻稉搴濈瑓鏉炶棄鍙嗛崣?*/
  attachments?: ChatAttachment[]
  /** 閸欘垶鈧绱伴張顒佹蒋濞戝牊浼呯€电懓绨查惃?Token 娴ｈ法鏁ょ紒鐔活吀閿涘牆顩ч崥搴ｎ伂閸?DONE 娴滃娆㈡稉顓＄箲閸ョ儑绱?*/
  tokenUsage?: TokenUsage
  modelId?: string
  /** 閸欘垶鈧绱伴張顒佹蒋濞戝牊浼呯€电懓绨查惃鍕侀崹?ID閿涘牆顩ч崣顖滄暏閿涘绱濋悽銊ょ艾濞戝牊浼呯痪褑鐨熺拠鏇炵潔缁€?*/
  preferredProviderId?: string
  /** 閸欘垶鈧绱伴張顒冪枂閹笛嗩攽濞戝寮烽崚鎵畱閻儴鐦戞惔?/ 閺傚洦銆傜粵澶嬫降濠ф劖鎲崇憰?*/
  sources?: SourceSummary[]
  /** 閸欘垶鈧绱伴張顒冪枂閹笛嗩攽濞戝寮烽崚鎵畱瀹搞儱鍙跨拫鍐暏閹芥顩﹂崚妤勩€?*/
  toolsSummary?: ToolCallSummary[]
  /** 閸欘垶鈧绱伴張顒冪枂 ReAct 濮濄儵顎冩惔蹇撳灙閿涘牊娴涙禒?toolsSummary閿涘瞼绮ㄩ弸鍕鐏炴洜銇氶幒銊ф倞鏉╁洨鈻奸敍?*/
  reactSteps?: ReactStepDto[]
  /** 濞戝牊浼呴弰顖氭儊瀹稿弶濮岄崣鐙呯礄闂€鎸庣Х閹垰婧€閺咁垽绱濋崜宥囶伂閺堫剙婀撮悩鑸碘偓渚婄礆 */
  collapsed?: boolean
  /** 閻劍鍩涢崣宥夘洯閻樿埖鈧緤绱欓崜宥囶伂閺堫剙婀撮悩鑸碘偓渚婄礉娑撳秵瀵旀稊鍛閸掓澘鎮楃粩顖ょ礆 */
  feedbackStatus?: 'liked' | 'disliked' | null
  /** 瀹搞儱鍙跨涵顔款吇鐠囬攱鐪伴弫鐗堝祦閿涘牊鏁幐浣割樋娑擃亜鑻熼崣鎴犫€樼拋銈忕礆 */
  toolConfirmations?: Record<string, ToolConfirmationRequest>
  /** 瀹搞儱鍙跨涵顔款吇鐟欙絽鍠呯紒鎾寸亯閺勭姴鐨?*/
  toolConfirmationResolutions?: Record<string, 'approved' | 'rejected' | 'expired'>
}

/** A2UI 缂佸嫪娆㈤懞鍌滃仯閿涘牓鍋﹂幒銉ㄣ€冮敍?*/
export interface A2uiComponent {
  id: string
  type: string
  properties: Record<string, unknown>
  children: string[]
  signal?: A2uiSignal
}

/** A2UI 娣団€冲娇 */
export interface A2uiSignal {
  name: string
  payload: Record<string, unknown>
}

/** A2UI 娣団€冲娇娑撳﹣绗呴弬鍥风礄閸撳秶顏張顒€婀存稉搴℃礀娴肩姵妞傞梽鍕敨閿?*/
export interface A2uiSignalContext {
  componentId?: string
  entryId?: string
  traceId?: string
  signalName?: string
}

/** A2UI 娴溿倓绨版潻鎰攽閺冨墎濮搁幀?*/
export interface A2uiSignalRuntime {
  status: 'idle' | 'sending' | 'success' | 'error'
  error?: string | null
  updatedAt: number
}

/** Token 濞戝牐鈧绮虹拋?*/
export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  modelId: string
  /** 閸欘垶鈧绱版惔鏇炵湴 Provider 閺嶅洩鐦戦敍鍫㈡暏娴滃氦鐨熺拠鏇氱瑢鐠伮ゅ瀭鐎电澶勯敍?*/
  providerId?: string
}

/** 閻劍鍩涚拋鍓х枂 */
export interface UserSettings {
  theme: 'light' | 'dark' | 'system'
  language: string
  layoutDensity?: 'compact' | 'standard'
  fontSize?: 'small' | 'medium' | 'large'
  timeFormat?: '12h' | '24h'
  showTokenUsage?: boolean
  autoExpandCodeBlocks?: boolean
  collapseLongReplies?: boolean
  collapseThreshold?: number
  enableStreaming?: boolean
  enableFunctionCall?: boolean
  enableKnowledgeBase?: boolean
  enableToolCall?: boolean
  sessionTimeout?: number
  maxRecentTurns?: number
  workingMemoryBudget?: number
  compressionThreshold?: number
  maxRetentionDays?: number
}

/** SSE token 娴滃娆?*/
export interface SseTokenEvent {
  content: string
  index: number
}

/** 閹恒劎鎮婃潻鍥┾柤娴滃娆㈢猾璇茬€?閳?娑撳骸鎮楃粩?pushReactStepEvent 鐎靛綊缍?*/
export type ReasoningEventType =
  | 'AGENT_START'
  | 'THOUGHT'
  | 'TOOL_CALL'
  | 'OBSERVATION'
  | 'ANSWER'
  | 'SUSPEND'
  | 'RESUME'
  | 'ANSWER_FINALIZED'

/** 閹恒劎鎮婃潻鍥┾柤娴滃娆㈤敍鍦asoning Timeline閿?*/
export interface ReasoningEvent {
  id: string
  type: ReasoningEventType
  title: string
  description?: string
  toolName?: string
  createdAt: string
  extra?: Record<string, any>
}

// ===== ReactStep 缁鐎风€规矮绠?閳?娑撳骸鎮楃粩?ReactStepSerializer 鐎靛綊缍?=====

/** ReactStep 濮濄儵顎冪猾璇茬€?*/
export type ReactStepType = 'THOUGHT' | 'TOOL_CALL' | 'OBSERVATION' | 'ANSWER' | 'SUSPEND' | 'RESUME'

/** ReactStep 閸╄櫣顢呯€涙顔?*/
interface ReactStepBase {
  type: ReactStepType
  index: number
}

/** 閹恒劎鎮婇幀婵娾偓鍐╊劄妤?*/
export interface ThoughtStep extends ReactStepBase {
  type: 'THOUGHT'
  content: string
}

/** 瀹搞儱鍙跨拫鍐暏濮濄儵顎?*/
export interface ToolCallStep extends ReactStepBase {
  type: 'TOOL_CALL'
  toolId: string
  /** 瀹搞儱鍙块弰鍓с仛閸氬秶袨閿涘牏鏁ら幋宄板讲鐠囦紮绱濇俊?"閸掓稑缂撳鍛"閿涘绱濇稉铏光敄閺冭泛娲栭柅鈧崚?toolId */
  toolName?: string
  inputSummary: string
  latencyMs: number
}

/** 瀹搞儱鍙跨憴鍌氱檪濮濄儵顎?*/
export interface ObservationStep extends ReactStepBase {
  type: 'OBSERVATION'
  toolId: string
  /** 瀹搞儱鍙块弰鍓с仛閸氬秶袨閿涘牏鏁ら幋宄板讲鐠囦紮绱氶敍灞艰礋缁岀儤妞傞崶鐐衡偓鈧崚?toolId */
  toolName?: string
  success: boolean
  outputSummary: string
  tokensUsed: number
}

/** 閺堚偓缂佸牆娲栫粵鏃€顒炴?*/
export interface AnswerStep extends ReactStepBase {
  type: 'ANSWER'
  content: string
}

/** 閹稿倽鎹ｅ銉╊€?*/
export interface SuspendStep extends ReactStepBase {
  type: 'SUSPEND'
  reason: string
  suspendedAt: string
}

/** 閹垹顦插銉╊€?*/
export interface ResumeStep extends ReactStepBase {
  type: 'RESUME'
  resumedAt: string
  suspendDurationMs: number
}

/** ReactStep 閼辨柨鎮庣猾璇茬€?*/
export type ReactStepDto = ThoughtStep | ToolCallStep | ObservationStep | AnswerStep | SuspendStep | ResumeStep

/** SSE 鐎瑰本鍨氭禍瀣╂ */
export interface SseDoneEvent {
  entryId: string
  /** 閸欘垶鈧绱扮€瑰本鏆ｅ☉鍫熶紖閸愬懎顔愰敍鍫ユ姜濞翠礁绱￠崫宥呯安閺冨墎鏁遍崥搴ｎ伂閻╁瓨甯存潻鏂挎礀閿?*/
  content?: string
  /** 閸欘垶鈧绱伴張顒冪枂閸ョ偟鐡熼梽鍕敨閻?A2UI 缂佸嫪娆㈤弽鎴濇彥閻?*/
  a2uiComponents?: A2uiComponent[]
  /** 閸欘垶鈧绱癟oken 娴ｈ法鏁ょ紒鐔活吀 */
  tokenUsage?: TokenUsage
  /** 閸欘垶鈧绱伴懕姘値閸氬海娈?Token 娴ｈ法鏁ゅ鍌濐洣閿涘潟nput/output/total閿涘绱濇稉搴℃倵缁?doneData.usage 鐎靛綊缍?*/
  usage?: {
    inputTokens: number
    outputTokens: number
    totalTokens: number
  }
  /** 閸欘垶鈧绱伴張顒冪枂閹笛嗩攽鐎电懓绨查惃?Trace Id閿涘牆顩ч崥搴ｎ伂閺堝绻戦崶鐑囩礆 */
  traceId?: string
  /** 閸欘垶鈧绱版导姘崇樈 ID閿涘牆鎮楃粩顖濈箲閸ョ儑绱濋悽銊ょ艾閸撳秶顏崥灞绢劄閿?*/
  completionMode?: CompletionMode
  resumedFromTraceId?: string
  sessionId?: string
  /** 閸欘垶鈧绱板☉鍫熶紖鐎瑰本鍨氶弮鍫曟？閹寸绱欏В顐ゎ潡閿涘苯鎮楃粩顖濈箲閸ョ儑绱?*/
  timestamp?: number
  /** 閸欘垶鈧绱伴張顒冪枂閹恒劎鎮婂鍌濐洣閿涘牆鍑″Λ鈧槐銏ｎ唶韫?瀹搞儱鍙跨拫鍐暏缁涘绱?*/
  reasoningSummary?: string
  /** 閸欘垶鈧绱伴幐澶婎樋濡剝鈧胶绮ㄩ弸鍕箲閸ョ偟娈戠€瑰本鏆ｉ崘鍛啇閸掓銆冮敍鍫濆悑鐎硅婀弶銉﹀珖鐏炴洩绱?*/
  contents?: Array<{
    type: 'TEXT' | 'IMAGE' | 'AUDIO' | 'VIDEO' | 'FILE'
    text?: string
    url?: string
    mimeType?: string
    metadata?: Record<string, any>
  }>
  /** 閸欘垶鈧绱伴惌銉ㄧ槕鎼?/ 閺傚洦銆?/ 瀹搞儱鍙跨粵澶嬫降濠ф劖鎲崇憰渚婄礄閻劋绨潪濠氬櫤 UI 鐏炴洜銇氶敍?*/
  sources?: SourceSummary[]
  /** 閸欘垶鈧绱伴張顒冪枂瀹搞儱鍙跨拫鍐暏閹芥顩﹂崚妤勩€冮敍鍫熸降閼?Trace ToolCallStep 閼辨艾鎮庨敍?*/
  toolsSummary?: ToolCallSummary[]
  /** 閸欘垶鈧绱伴張顒冪枂 ReAct 濮濄儵顎冩惔蹇撳灙閿涘牏绮ㄩ弸鍕閹恒劎鎮婃潻鍥┾柤閿?*/
  reactSteps?: ReactStepDto[]
}

/** SSE 闁挎瑨顕ゆ禍瀣╂ */
export interface SseErrorEvent {
  code: number
  message: string
  /** 閸欘垶鈧绱伴柨娆掝嚖鐎电懓绨查惃?Trace Id閿涘奔绌舵禍搴″缁旑垵鐑︽潪顒冪殶鐠?*/
  traceId?: string
}

/** 瀹搞儱鍙跨涵顔款吇鐠囬攱鐪伴敍鍦玈E 娴滃娆?payload閿?*/
export interface ToolConfirmationRequest {
  requestId: string
  toolId: string
  toolName: string
  riskLevel: 'HIGH' | 'CRITICAL'
  approvalMode: string
  message: string
  timestamp: string
}

/** SSE 婵帊缍嬮弫鐗堝祦娴滃娆㈤敍鍫熷焻閸ュ墽鐡戞禍宀冪箻閸掕埖鏆熼幑顕€鈧俺绻冮悪顒傜彌娴滃娆㈡导鐘虹翻閿涘矂浼╅崗宥堫潶閹搭亝鏌囬敍?*/
export interface SseMediaEvent {
  /** 婵帊缍嬬€涙顔岄崥宥忕礄婵?screenshot閿?*/
  field: string
  /** MIME 缁鐎烽敍鍫濐洤 image/png閿?*/
  mimeType: string
  /** Base64 缂傛牜鐖滈惃鍕崯娴ｆ挻鏆熼幑?*/
  data: string
}

/** 闂堢偞绁﹀蹇氫喊婢垛晛鎼锋惔?*/
export interface SseTranscriptionEvent {
  sessionId: string
  text: string
  entryId?: string
}

export interface ChatResponse {
  entryId: string
  content: string
  a2uiComponents?: A2uiComponent[]
  tokenUsage?: TokenUsage
  traceId?: string
  /** 閺堫剝鐤嗙€电鐦芥稉顓濆▏閻劌鍩岄惃鍕叀鐠囧棗绨?/ 閺傚洦銆傞弶銉︾爱缁涘绱欓悽鍗炴倵缁旑垵绻戦崶鐑囩礉閸撳秶顏崣顏勪粵鏉炲鍣虹仦鏇犮仛閿?*/
  completionMode?: CompletionMode
  resumedFromTraceId?: string
  sources?: SourceSummary[]
}

/** 閹笛嗩攽閺夈儲绨幗妯款洣閿涘牏鐓＄拠鍡楃氨 / 閺傚洦銆?/ 瀹搞儱鍙?/ 瀹搞儰缍斿ù渚婄礆 */
export interface SourceSummary {
  type: 'knowledgeBase' | 'document' | 'tool' | 'workflow'
  id: string
  name: string
  extra?: Record<string, unknown>
}

/** 瀹搞儱鍙跨拫鍐暏閹芥顩﹂敍鍫㈡暏娴滃骸宕熸潪顔藉⒔鐞涘本顩х憰浣风瑢鐠嬪啳鐦憴鍡楁禈閿?*/
export interface ToolCallSummary {
  toolId: string
  action?: string
  success: boolean
  latencyMs: number
  hasMoreSteps?: boolean
  /** 瀹搞儱鍙跨拫鍐暏閻ㄥ嫯绶崗銉ュ棘閺佺増鎲崇憰渚婄礄閹搭亝鏌囩仦鏇犮仛閿涘瞼鏁遍崥搴ｎ伂 done 娴滃娆㈡潻鏂挎礀閿?*/
  inputSummary?: string
  /** 瀹搞儱鍙跨拫鍐暏閻ㄥ嫯绶崙铏圭波閺嬫粍鎲崇憰渚婄礄閹搭亝鏌囩仦鏇犮仛閿涘瞼鏁遍崥搴ｎ伂 done 娴滃娆㈡潻鏂挎礀閿?*/
  outputSummary?: string
}

/** 缂佺喍绔撮柨娆掝嚖閸濆秴绨?*/
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}

/** 娴兼俺鐦介柊宥囩枂閿涘牊膩閸?濞撯晛瀹?娑撳娣０鍕暬/閻儴鐦戞惔鎾烩偓澶嬪閿?*/
export interface SessionConfig {
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  maxSteps?: number
  maxDurationSeconds?: number
  knowledgeBaseIds?: string[]
}

// ========== 濡€虫健 19: Web UI 閸旂喕鍏樻い鐢告桨缁鐎?==========

/** 閻儴鐦戞惔?*/
export interface KnowledgeBase {
  id: string
  name: string
  description: string
  embeddingModel: string
  rerankerModel?: string
  chunkingStrategy?: string
  tags?: string[]
  documentCount: number
  totalChunks: number
  createdAt: string
  updatedAt: string
}

/** 閸掓稑缂撻惌銉ㄧ槕鎼存捁顕Ч?*/
export interface CreateKbRequest {
  name: string
  description: string
  embeddingModel?: string
}

/** 閺囧瓨鏌婇惌銉ㄧ槕鎼存捁顕Ч?*/
export interface UpdateKbRequest {
  name?: string
  description?: string
  embeddingModel?: string
  rerankerModel?: string | null
  chunkingStrategy?: string
  chunkingConfig?: Record<string, unknown>
  tags?: string[]
}

/** 閻儴鐦戞惔鎾存瀮濡?*/
export interface KbDocument {
  id: string
  knowledgeBaseId: string
  fileName: string
  fileSize: number
  mimeType: string
  status: 'UPLOADING' | 'PARSING' | 'CHUNKING' | 'INDEXING' | 'EXTRACTING' | 'READY' | 'UPDATING' | 'DELETING' | 'ERROR'
  chunkCount: number
  errorMessage?: string | null
  createdAt: string
  updatedAt: string
}

/** 閺傚洦銆傞崚鍡楁健 */
export interface DocumentChunk {
  id: string
  documentId: string
  knowledgeBaseId: string
  content: string
  contextPrefix?: string
  chunkIndex: number
  startOffset: number
  endOffset: number
  tokenCount: number
  contentHash: string
  headingHierarchy?: string[]
  pageNumber?: number
  metadata?: Record<string, unknown>
  createdAt: string
}

/** 閻儴鐦戞惔鎾剁埠鐠佲€蹭繆閹?*/
export interface KbStats {
  documentCount: number
  totalChunks: number
  totalSize: number
  indexStatus: 'HEALTHY' | 'PROCESSING' | 'PARTIAL_FAILURE'
  processingDocuments: number
  errorDocuments: number
}

/** 婢跺嫮鎮婇弮銉ョ箶 */
export interface ProcessingLog {
  id: string
  documentId: string
  stage: 'UPLOAD' | 'PARSE' | 'CHUNK' | 'INDEX' | 'COMPLETE' | 'ERROR'
  message: string
  timestamp: string
  error?: string
}

/** 濞村鐦Λ鈧槐銏㈢波閺?*/
export interface TestRetrievalResult {
  query: string
  chunks: Array<{
    chunkId: string
    documentId: string
    documentName: string
    content: string
    similarity: number
    metadata?: Record<string, unknown>
  }>
  answer?: string
}

/** Skill 閹芥顩?*/
export interface SkillSummary {
  id: string
  name: string
  description: string
  version: string
  source: { type: 'Builtin' | 'UserDefined' | 'AutoGenerated' | 'Marketplace'; [key: string]: unknown }
  metadata?: Record<string, string>
}

/** Skill 鐠囷附鍎?*/
export interface SkillDetail extends SkillSummary {
  instructions: string
  suggestedTools: string[]
  metadata: Record<string, string>
}

/** MCP Server */
export interface McpServer {
  name: string
  state:
    | 'DISCONNECTED'
    | 'CONNECTING'
    | 'CONNECTED'
    | 'RECONNECTING'
    | 'INITIALIZING'
    | 'HEALTH_CHECK'
    | 'DISCONNECTING'
  toolCount: number
  connectedSince?: string
  lastError?: string
  config?: McpServerConfig
}

/** MCP Server 闁板秶鐤?*/
export interface McpServerConfig {
  transport: 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY' | 'stdio' | 'sse'
  command?: string
  args?: string[]
  env?: Record<string, string>
  baseUrl?: string
  url?: string
  timeoutSeconds?: number
  timeout?: number
  maxRetries?: number
  autoConnect?: boolean
  reconnect?: boolean
  reconnectDelay?: number
  maxReconnectAttempts?: number
  healthCheckInterval?: number
}

/** MCP 瀹搞儱鍙?*/
export interface McpTool {
  id: string
  name: string
  description: string
  /** 鏉堟挸鍙嗛崣鍌涙殶 JSON Schema閿涘牆褰查柅澶涚礉閸氬海顏?ToolContract 鎼村繐鍨崠鏍箲閸ョ儑绱?*/
  inputSchema?: Record<string, any>
}

/** 鏉炪劏鎶楅崚妤勩€冩い?*/
export interface TraceItem {
  id: string
  sessionId: string
  userMessage: string
  success: boolean
  totalSteps: number
  totalTokens: number
  durationMs: number
  createdAt: string
}

/** 鏉炪劏鎶楃拠锔藉剰 */
export interface TraceDetail extends TraceItem {
  finalOutput?: string
  errorMessage?: string
  terminationReason?: string
  modelId?: string
}

/** 鏉炪劏鎶楀銉╊€?*/
export interface TraceStep {
  id: string
  stepIndex: number
  phaseBefore: string
  phaseAfter: string
  actionType: string
  actionJson?: string
  toolId?: string
  toolInputJson?: string
  toolOutput?: string
  success: boolean
  blocked: boolean
  blockReason?: string
  tokensUsed: number
  latencyMs: number
  createdAt: string
}

/** 閸掑棝銆夌紒鎾寸亯 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 瀹搞儰缍斿ù浣稿灙鐞涖劑銆?*/
export interface WorkflowItem {
  id: string
  name: string
  description: string
  enabled: boolean
  triggerTypes: string[]
  version: string
  tags?: string[]
}

/** 瀹搞儰缍斿ù浣界翻閸忋儱寮弫鏉跨暰娑斿绱欑€靛綊缍堥崥搴ｎ伂 WorkflowInputParam record閿?*/
export interface WorkflowInputParam {
  name: string
  type: 'string' | 'number' | 'boolean' | 'list' | 'map'
  required: boolean
  defaultValue?: unknown
  description?: string
}

/** 瀹搞儰缍斿ù浣筋嚊閹?*/
export interface WorkflowDetail extends WorkflowItem {
  triggers: unknown[]
  inputs: Record<string, WorkflowInputParam>
  steps: unknown[]
  metadata: Record<string, string>
  yaml?: string
}

/** 瀹搞儰缍斿ù浣瑰⒔鐞涘矁顔囪ぐ?*/
export interface WorkflowExecution {
  id: string
  workflowId: string
  state: 'CREATED' | 'RUNNING' | 'PAUSED' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  completedStepIds: string[]
  pendingApprovalStepId?: string
  startedAt?: string
  completedAt?: string
  failureReason?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowExecutionsSnapshot {
  executions: WorkflowExecution[]
}

/** 瀹搞儰缍斿ù浣割吀鐠佲€茬皑娴犲墎琚崹?*/
export type WorkflowEventType =
  | 'INSTANCE_CREATED'
  | 'INSTANCE_STATE_CHANGED'
  | 'STEP_STARTED'
  | 'STEP_COMPLETED'
  | 'STEP_FAILED'
  | 'STEP_SKIPPED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'

/** 瀹搞儰缍斿ù浣割吀鐠佲€茬皑娴?*/
export interface WorkflowEvent {
  id: string
  instanceId: string
  workflowId: string
  type: WorkflowEventType
  stepId?: string
  dataJson?: string
  createdAt: string
}

export interface WorkflowTimelineSnapshot {
  events: WorkflowEvent[]
}

/** 鐎光剝澹掔拠閿嬬湴 */
export interface ApprovalRequest {
  decision: 'APPROVED' | 'REJECTED'
  decidedBy: string
  reason?: string
}

/** 瀹搞儰缍斿ù浣诡劄妤犮倖澧界悰灞炬）韫?*/
export interface StepLog {
  id: string
  instanceId: string
  stepId: string
  stepType: string
  state: 'COMPLETED' | 'FAILED' | 'SKIPPED'
  attempt: number
  retryCount: number
  inputJson?: string
  outputJson?: string
  errorMessage?: string
  startedAt: string
  completedAt: string
  durationMs: number
  createdAt: string
}

export interface WorkflowStepLogsSnapshot {
  stepLogs: StepLog[]
}

// ========== 瀹搞儰缍斿ù浣瑰灇閻旂喎瀵查棁鈧Ч鍌滆閸ㄥ鐣炬稊?==========

/** 瀹搞儰缍斿ù浣瑰⒔鐞涘瞼绮虹拋?*/
export interface WorkflowStats {
  workflowId: string
  totalExecutions: number
  successCount: number
  failedCount: number
  avgDurationMs: number
  recentTrend: DailyTrend[]
}

/** 濮ｅ繑妫╅幍褑顢戠搾瀣◢ */
export interface DailyTrend {
  date: string
  count: number
  successCount: number
}

/** 濮濄儵顎冮幍褑顢戠紒鐔活吀 */
export interface StepStats {
  stepId: string
  stepType: string
  executionCount: number
  successRate: number
  avgDurationMs: number
  maxDurationMs: number
  totalRetries: number
}

/** 濮濄儵顎冩潏鎾冲毉鐠囷附鍎?*/
export interface StepOutput {
  stepId: string
  output: unknown
  durationMs: number
  retryCount: number
  errorMessage?: string
  state: string
}

/** DAG 閺佺増宓?*/
export interface DagData {
  nodes: DagNode[]
  edges: DagEdge[]
  levels: string[][]
}

/** DAG 閼哄倻鍋?*/
export interface DagNode {
  id: string
  name: string
  type: string
  dependsOn: string[]
}

/** DAG 鏉?*/
export interface DagEdge {
  from: string
  to: string
}

/** 鐠囨洝绻嶇悰宀€绮ㄩ弸?*/
export interface DryRunResult {
  steps: DryRunStepTrace[]
  dagOrder: string[]
  warnings: string[]
}

/** 鐠囨洝绻嶇悰灞绢劄妤犮倛寤烘潻?*/
export interface DryRunStepTrace {
  stepId: string
  stepName: string
  stepType: string
  resolvedParams: Record<string, unknown>
  conditionResult?: boolean
  branch?: string
  loopIterations?: number
}

/** YAML 閺嶏繝鐛欓崫宥呯安 */
export interface ValidationResponse {
  valid: boolean
  errors: string[]
  warnings: string[]
  dagValid: boolean
  dagError?: string
}

/** 濮濄儵顎冪猾璇茬€?Schema */
export interface StepTypeSchema {
  stepType: string
  label: string
  description: string
  params: ParamSchema[]
}

/** 閸欏倹鏆?Schema */
export interface ParamSchema {
  name: string
  type: string
  required: boolean
  defaultValue?: unknown
  description?: string
  inputType?: string
  options?: OptionItem[]
  placeholder?: string
  example?: string
  validationPattern?: string
  validationMessage?: string
}

/** 闁銆嶆い?*/
export interface OptionItem {
  value: string
  label: string
}

/** Agent 閸掓銆冩い?*/
export type AgentType = 'default' | 'custom' | 'workflow' | 'marketplace'

export interface AgentLlmConfig {
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
}

export interface AgentKnowledgeBaseBinding {
  id: string
  name: string
  topK?: number
  maxContextTokens?: number
}

export interface AgentSummary {
  id: string
  name: string
  description?: string
  type: AgentType
  preferredProviderId?: string
  knowledgeBaseCount: number
  enabled: boolean
  status: string
  updatedAt: string
  createdAt: string
  tags?: string[]
  avatar?: string
  /** Agent 閺夈儲绨猾璇茬€烽敍鍦rkdownDefined / Builtin 缁涘绱?*/
  source?: string
}

/** Agent 鐠囷附鍎?*/
export interface AgentDetail extends AgentSummary {
  systemPrompt: string
  llmConfig: AgentLlmConfig
  knowledgeBases: AgentKnowledgeBaseBinding[]
  enabledTools: string[]
  metadata?: Record<string, unknown>
}

export interface CreateAgentRequest {
  name: string
  description?: string
  systemPrompt?: string
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
  knowledgeBaseIds?: string[]
  toolIds?: string[]
  tags?: string[]
  metadata?: Record<string, unknown>
}

export interface UpdateAgentRequest {
  name?: string
  description?: string
  systemPrompt?: string
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
  knowledgeBaseIds?: string[]
  toolIds?: string[]
  tags?: string[]
  metadata?: Record<string, unknown>
}

/** Tool 閹芥顩?*/
export interface ToolSummary {
  id: string
  name: string
  displayName?: string
  description?: string
  type: 'PLUGIN' | 'SKILL' | 'MCP'
  source: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  enabled: boolean
  idempotent?: boolean
}

/** Tool 鐠囷附鍎?*/
export interface ToolDetail extends ToolSummary {
  inputSchema?: Record<string, any>
  outputSchema?: Record<string, any>
  budget?: {
    timeoutSeconds?: number
    maxRetries?: number
    maxCostCents?: number
  }
  exportable?: boolean
  tags?: string[]
  sideEffects?: string[]
  metadata?: Record<string, unknown>
}

/** Tool 濞村鐦拠閿嬬湴 */
export interface ToolTestRequest {
  toolId: string
  input?: Record<string, any>
}

/** Tool 濞村鐦崫宥呯安 */
export interface ToolTestResponse {
  success: boolean
  output?: any
  error?: string
  executionTimeMs?: number
  meta?: {
    durationMs?: number
    toolId?: string
    action?: string
  }
}

/** Analytics 閻劑鍣虹紒鐔活吀 */
export interface UsageStats {
  totalRequests: number
  totalTokens: number
  inputTokens: number
  outputTokens: number
  estimatedCost?: number
  timeRange: {
    from: string
    to: string
  }
  dailyStats?: Array<{
    date: string
    requests: number
    tokens: number
    inputTokens: number
    outputTokens: number
    cost?: number
  }>
}

/**
 * Token 濞戝牐鈧绮虹拋鈽呯礄Trace 缂佹潙瀹抽敍? *
 * 鐎佃瀵氱€规碍妞傞梻纾嬪瘱閸ユ潙鍞撮惃?Trace 鏉╂稖顢戦懕姘値缂佺喕顓搁敍宀€鏁ゆ禍?Trace 妞ょ敻娼?Token 閸掑棙鐎介妴? */
export interface TokenConsumptionStats {
  /** 缂佺喕顓搁弮鍫曟？閼煎啫娲块崘鍛畱 Trace 閺佷即鍣?*/
  traceCount: number
  /** 閹碘偓閺?Trace 閻ㄥ嫭鈧?Token 閺?*/
  totalTokens: number
  /** 閹碘偓閺?Trace 閻ㄥ嫭鈧槒绶崗?Token 閺?*/
  totalInputTokens: number
  /** 閹碘偓閺?Trace 閻ㄥ嫭鈧槒绶崙?Token 閺?*/
  totalOutputTokens: number
  /** 濮ｅ繑娼?Trace 楠炲啿娼?Token 閺?*/
  avgTokensPerTrace: number
  /** 閸楁洘娼?Trace 閻ㄥ嫭娓舵径?Token 閺?*/
  maxTokens: number
  /** 閹存劕濮涢惃?Trace 閺佷即鍣?*/
  successCount: number
  /** 楠炲啿娼庨懓妤佹閿涘牊顕犵粔鎺炵礆 */
  avgDurationMs: number
}

/** Analytics Agent 缂佺喕顓?*/
export interface AgentStats {
  agentId: string
  agentName: string
  callCount: number
  avgResponseTime: number
  failureRate: number
  totalTokens: number
}

/** Analytics 閻儴鐦戞惔鎾剁埠鐠?*/
export interface KnowledgeBaseStats {
  kbId: string
  kbName: string
  retrievalCount: number
  hitRate?: number
  avgRetrievalTime?: number
}

/** Analytics 瀹搞儱鍙跨紒鐔活吀 */
export interface ToolStats {
  toolId: string
  toolName: string
  callCount: number
  successCount: number
  failureCount: number
  avgLatency: number
}


/** Provider 閼宠棄濮忕猾璇茬€?*/
export type ProviderCapability = 'STREAMING' | 'FUNCTION_CALLING' | 'EMBEDDING' | 'VISION' | 'AUDIO' | 'RERANK'

// ========== 濡€虫健 20: Observability 缂佺喕顓告稉搴ょ槑娴?==========

/**
 * 鏉炪劏鎶楀鍌濐潔缂佺喕顓搁弫鐗堝祦
 *
 * 閻劋绨?Trace 閸掓銆冩い鐢搞€婇柈銊ф畱缂佺喕顓搁崡锛勫閸栧搫鐓欓敍灞界潔缁€鐑樻殻娴ｆ捁绻嶇悰灞惧剰閸愮偣鈧? */
export interface OverviewStats {
  /** 鏉炪劏鎶楅幀缁樻殶 */
  totalTraces: number
  /** 閹存劕濮涙潪銊ㄦ姉閺佷即鍣?*/
  successCount: number
  /** 婢惰精瑙︽潪銊ㄦ姉閺佷即鍣?*/
  failureCount: number
  /** 閹存劕濮涢悳鍥风礄0-1 鐏忓繑鏆熼敍?*/
  successRate: number
  /** 楠炲啿娼庡銉╊€冮弫?*/
  avgSteps: number
  /** 楠炲啿娼庨懓妤佹閿涘牊顕犵粔鎺炵礆 */
  avgDurationMs: number
  /** 閹?Token 濞戝牐鈧?*/
  totalTokens: number
  /** 楠炲啿娼庡В蹇旀蒋鏉炪劏鎶?Token 濞戝牐鈧?*/
  avgTokens: number
}

/**
 * 瀹搞儱鍙跨拫鍐暏娴ｈ法鏁ょ紒鐔活吀
 *
 * 閻劋绨銉ュ徔缂佺喕顓搁崚妤勩€冮敍灞界潔缁€鍝勬倗瀹搞儱鍙块惃鍕殶閻劋绗岄幋鎰閹懎鍠岄妴? */
export interface ToolUsageStats {
  /** 瀹搞儱鍙块崬顖欑閺嶅洩鐦?*/
  toolId: string
  /** 閹槒鐨熼悽銊︻偧閺?*/
  callCount: number
  /** 閹存劕濮涚拫鍐暏濞嗏剝鏆?*/
  successCount: number
  /** 婢惰精瑙︾拫鍐暏濞嗏剝鏆?*/
  failureCount: number
  /** 閹存劕濮涢悳鍥风礄0-1 鐏忓繑鏆熼敍?*/
  successRate: number
  /** 楠炲啿娼庣拫鍐暏閼版妞傞敍鍫燁嚑缁夋帪绱?*/
  avgDurationMs: number
}

/**
 * 鏉炪劏鎶楃粋鑽ゅ殠鐠囧嫪鍙婄紒鎾寸亯
 *
 * 鐎电懓宕熼弶?Trace 閻ㄥ嫬顦跨紒鏉戝鐠愩劑鍣虹拠鍕強閿涘瞼鏁ゆ禍搴ゎ嚊閹懘銆夌仦鏇犮仛閵? */
export interface EvaluationResult {
  /** 鐞氼偉鐦庢导鎵畱鏉炪劏鎶?ID */
  traceId: string
  /** 鐠囧嫪鍙婇弮鍫曟？閿涘湜SO 8601閿?*/
  evaluatedAt: string
  /** 瀹搞儱鍙块柅澶嬪閸氬牏鎮婇幀褑鐦庨崚鍡礄0-1閿?*/
  toolSelectionScore: number
  /** 閸欏倹鏆熼崥鍫熺《閹傜瑢楠炲倻鐡戦幀褑鐦庨崚鍡礄0-1閿?*/
  parameterValidityScore: number
  /** 濮濄儵顎冮弫浼村櫤娑撳海绮ㄩ弸鍕櫏閻滃洩鐦庨崚鍡礄0-1閿?*/
  stepEfficiencyScore: number
  /** 缁涙牜鏆愭稉搴㈠Б閺嶅繐鎮庣憴鍕偓褑鐦庨崚鍡礄0-1閿?*/
  policyComplianceScore: number
  /** Token 娴ｈ法鏁ら弫鍫㈠芳鐠囧嫬鍨庨敍?-1閿?*/
  tokenEfficiencyScore: number
  /** 缂佺厧鎮庣拠鍕瀻閿?-1閿?*/
  overallScore: number
  /** 鐎圭偤妾幍褑顢戝銉╊€冮弫?*/
  actualSteps: number
  /** 鐎圭偤妾☉鍫ｂ偓?Token 閺?*/
  actualTokens: number
  /** 鏉╂繆顫夌拠瀛樻閸掓銆冮敍鍫濐洤鐎涙ê婀梻顕€顣介敍?*/
  violations: string[]
  /** 娴兼ê瀵插楦款唴閸掓銆?*/
  suggestions: string[]
}

// ========== 閻儴鐦戞惔鎾村珛閹锋垝绗傛导鐙呯窗閸撳秶顏張顒€婀寸猾璇茬€?==========

/**
 * 娑撳﹣绱堕弬鍥︽閺夛紕娲伴敍鍦瞤loadProgress 缂佸嫪娆㈡担璺ㄦ暏閿涘瞼鍑介崜宥囶伂閻樿埖鈧緤绱? */
export interface UploadFileItem {
  /** 閸撳秶顏悽鐔稿灇閻ㄥ嫬鏁稉鈧?ID閿涘牏鏁ゆ禍搴″灙鐞?key閿?*/
  id: string
  /** 閸樼喎顫?File 鐎电钖勫鏇犳暏閿涘牏鏁ゆ禍搴ㄥ櫢鐠囨洩绱?*/
  file: File
  /** 閺傚洣娆㈤崥?*/
  fileName: string
  /** 娑撳﹣绱堕悩鑸碘偓?*/
  status: 'waiting' | 'uploading' | 'success' | 'error'
  /** 闁挎瑨顕ゆ穱鈩冧紖閿涘牅绮?status === 'error' 閺冭埖婀侀崐纭风礆 */
  errorMessage?: string
}


// ========== 濡€虫健 25: 閹碘晛鐫嶇敮鍌氭簚缁鐎?==========

/** 閹碘晛鐫嶇猾璇茬€烽弸姘閿涘牆顕鎰倵缁?ExtensionType閿?*/
export type ExtensionType = 'SKILL' | 'AGENT' | 'WORKFLOW'

/** 閹碘晛鐫嶉崠鍛帗閺佺増宓侀敍鍫濐嚠姒绘劕鎮楃粩?ExtensionPackage record閿?*/
export interface ExtensionPackage {
  id: string
  name: string
  type: ExtensionType
  description: string
  version: string
  author: string
  repoUrl: string
  filePath: string
  tags: string[]
  requirements: string[]
  minZhiweiVersion: string
  createdAt: string
  updatedAt: string
  downloads: number
  verified: boolean
  /** 瀹告彃鐣ㄧ憗鍛畱閻楀牊婀伴敍鍫熸弓鐎瑰顥婇弮鏈佃礋 undefined閿?*/
  installedVersion?: string
  /** 閺勵垰鎯佸鎻掔暔鐟?*/
  installed: boolean
}

/** 閸氭垵鎮楅崗鐓庮啇閸掝偄鎮?*/
export type SkillPackage = ExtensionPackage

/** 鐎瑰鍙忛崣鎴犲箛閺夛紕娲?*/
export interface SecurityFinding {
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  category: string
  description: string
}

/** 鐎瑰鍙忛幍顐ｅ伎閹躲儱鎲?*/
export interface SecurityReport {
  findings: SecurityFinding[]
  overallRisk: 'LOW' | 'MEDIUM' | 'HIGH'
}

/** 鐎瑰顥婄紒鎾寸亯閿涘牆顕鎰倵缁?InstallResult record閿?*/
export interface InstallResult {
  success: boolean
  extensionId?: string
  extensionType?: ExtensionType
  securityReport?: SecurityReport
  requirements?: string[]
  errorMessage?: string
  requiresConfirmation: boolean
}

/** 閸氬海顏崚鍡涖€夌紒鎾寸亯閿涘牆顕?MarketplaceService.PagedResult閿?*/
export interface PagedResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}


// ========== Web UI 濞ｅ崬瀹崇拫鍐槸娑撳酣鍘ょ純顕嗙窗閺傛澘顤冪猾璇茬€?==========

/** 娑撳﹣绗呴弬鍥╃矋鐟佸懘顣╃憴鍫濇惙鎼?*/
export interface ContextPreviewResponse {
  segments: {
    systemPrompt: { content: string; tokens: number }
    contextMessages: { content: string; tokens: number }
    historyMessages: { content: string; tokens: number }
    currentUserPrompt: { content: string; tokens: number }
  }
  tokenBudget: TokenBudgetData
  totalTokens: number
  totalBudget: number
  degraded: boolean
}

/** Token 妫板嫮鐣婚弫鐗堝祦閿涘牆顕鎰倵缁?TokenBudget record閿?*/
export interface TokenBudgetData {
  systemPromptBudget: number
  historyBudget: number
  memoryBudget: number
  toolSchemaBudget: number
  toolResultBudget: number
  reservedBuffer: number
  systemPromptUsed: number
  historyUsed: number
  memoryUsed: number
  toolSchemaUsed: number
  toolResultUsed: number
}

/** 娓氭繆绂嗛崶鎹愬Ν閻?*/
export interface DependencyNode {
  id: string
  name: string
  type: 'AGENT' | 'SKILL' | 'TOOL'
  enabled: boolean
}

/** 娓氭繆绂嗛崶鎹愮珶 */
export interface DependencyEdge {
  source: string
  target: string
  relation: string
}

/** 娓氭繆绂嗛崶鎯ф惙鎼?*/
export interface DependencyGraphResponse {
  nodes: DependencyNode[]
  edges: DependencyEdge[]
}

/** Tool 鐠嬪啰鏁ょ紒鐔活吀妞?*/
export interface ToolCallStats {
  toolId: string
  toolName: string
  callCount: number
  successCount: number
  failureCount: number
  avgLatencyMs: number
}

/** Tool 鐠嬪啰鏁ゅВ蹇旀）鐡掑濞?*/
export interface ToolDailyTrend {
  date: string
  callCount: number
  successCount: number
  failureCount: number
}

/** Tool 缂佺喕顓?API 閸濆秴绨?*/
export interface ToolAnalyticsResponse {
  toolStats: ToolCallStats[]
  dailyTrend: ToolDailyTrend[]
}

/** 闁挎瑨顕ょ搾瀣◢濮ｅ繑妫╅弫鐗堝祦 */
export interface ErrorTrendDaily {
  date: string
  agentErrors: number
  toolErrors: number
  totalErrors: number
}

/** MCP 鏉╃偞甯撮弮銉ョ箶閺夛紕娲?*/
export interface McpConnectionLog {
  timestamp: string
  eventType: 'CONNECT' | 'DISCONNECT' | 'ERROR' | 'RECONNECT'
  description: string
}

/** Tool 濞村鐦崢鍡楀蕉鐠佹澘缍?*/
export interface ToolTestHistoryItem {
  id: string
  timestamp: number
  input: Record<string, any>
  result: ToolTestResponse
}


// ========== MCP Server 閻樿埖鈧?SSE 閹恒劑鈧胶琚崹?==========

/** MCP Server 閻樿埖鈧礁鎻╅悡褝绱橲SE mcp-status-snapshot 娴滃娆㈤弫鐗堝祦閿?*/
export interface McpStatusSnapshot {
  servers: Array<{
    serverName: string
    state: McpServer['state']
    connectedSince?: string
    lastError?: string
  }>
}

/** MCP Server 閻樿埖鈧礁褰夐崠鏍电礄SSE mcp-status-change 娴滃娆㈤弫鐗堝祦閿?*/
export interface McpStatusChange {
  serverName: string
  oldState: McpServer['state']
  newState: McpServer['state']
  timestamp: string
  error?: string
}

// ========== 闁氨鐓℃稉顓炵妇缁鐎风€规矮绠?==========

/** 闁氨鐓＄槐褎鈧儳鈻兼惔?*/
export type NotificationUrgency = 'HIGH' | 'MEDIUM' | 'LOW'

/** 闁氨鐓″鑼额嚢閻樿埖鈧?*/
export type NotificationReadStatus = 'UNREAD' | 'READ'

/** 闁氨鐓￠弶锛勬窗閿涘牆顕鎰倵缁?NotificationDto閿?*/
export interface NotificationItem {
  id: string
  userId: string
  typeId?: string
  urgency: NotificationUrgency
  contentJson: string
  channel: string
  readStatus: NotificationReadStatus
  status: string
  metadataJson?: string
  sentAt: string  // ISO 8601
}

/** 鐠囷附鍎忕憴锝嗙€界紒鎾寸亯 */
export interface ParsedDetail {
  type: 'TEXT' | 'MARKDOWN' | 'CARD' | 'UNKNOWN'
  /** TEXT: 鐎瑰本鏆ｉ弬鍥ㄦ拱; MARKDOWN: 閸樼喎顫?markdown; CARD: 閺?*/
  text?: string
  /** MARKDOWN: 濞撳弶鐓嬮崥搴ｆ畱 HTML */
  html?: string
  /** CARD: 閺嶅洭顣?*/
  title?: string
  /** CARD: 濮濓絾鏋?*/
  body?: string
  /** CARD: 閹垮秳缍旈幐澶愭尦閸掓銆?*/
  actions?: Array<{ label: string; url?: string }>
}



// ========== 鐠佹澘绻傜粻锛勬倞缁鐎风€规矮绠?==========

/** 鐠佹澘绻傜紒鐔活吀濮掑倽顫嶉敍鍫濐嚠鎼?MemoryStatsDto閿?*/
export interface MemoryStats {
  conversationCount: number
  entityCount: number
  entityCountByType: Record<string, number>
  relationCount: number
  templateCount: number
  preferenceCount: number
  forgettingLogCount: number
  lastForgettingTime: string | null
}

/** 缂佺喍绔撮幖婊呭偍缂佹挻鐏夐敍鍫濐嚠鎼?MemorySearchResultDto閿?*/
export interface MemorySearchResult {
  entityId: string
  entityType: string
  name: string
  description: string | null
  relevanceScore: number
}

/** 鐎圭偘缍嬮崚妤勩€冩い鐧哥礄鐎电懓绨?EntitySummaryDto閿?*/
export interface EntitySummary {
  id: string
  type: string
  typeLabel: string
  name: string
  description: string | null
  importanceScore: number
  accessCount: number
  version: number
  createdAt: string
  updatedAt: string
}

/** 鐎圭偘缍嬬拠锔藉剰閿涘牆顕惔?EntityDetailDto閿?*/
export interface EntityDetail {
  id: string
  type: string
  typeLabel: string
  name: string
  description: string | null
  properties: Record<string, unknown>
  version: number
  isCurrent: boolean
  validFrom: string
  validTo: string | null
  sourceConversationId: string | null
  extractionConfidence: number
  importanceScore: number
  accessCount: number
  lastAccessedAt: string | null
  createdAt: string
  updatedAt: string
}

/** 鐎圭偘缍嬮崚娑樼紦鐠囬攱鐪伴敍鍫濐嚠鎼?EntityCreateRequest閿?*/
export interface EntityCreateRequest {
  name: string
  type: string
  description?: string
  properties?: Record<string, unknown>
  importanceScore?: number
}

/** 鐎圭偘缍嬮弴瀛樻煀鐠囬攱鐪伴敍鍫濐嚠鎼?EntityUpdateRequest閿?*/
export interface EntityUpdateRequest {
  description?: string
  properties?: Record<string, unknown>
  importanceScore?: number
}

/** 鐎圭偘缍嬮崚妤勩€冮弻銉嚄閸欏倹鏆?*/
export interface EntityListParams {
  page?: number
  size?: number
  type?: string
  q?: string
  timeFrom?: string
  timeTo?: string
  sortBy?: string
  order?: string
}

/** 閸忓磭閮撮崚妤勩€冩い鐧哥礄鐎电懓绨?RelationDto閿?*/
export interface RelationItem {
  id: string
  sourceEntityId: string
  sourceEntityName: string
  sourceEntityType: string
  targetEntityId: string
  targetEntityName: string
  targetEntityType: string
  relationType: string
  strength: number
  validFrom: string
  validTo: string | null
  createdAt: string
}

/** 閸忓磭閮撮崚妤勩€冮弻銉嚄閸欏倹鏆?*/
export interface RelationListParams {
  page?: number
  size?: number
  entityId?: string
  relationType?: string
}

/** 鐎电鐦介崚妤勩€冩い鐧哥礄鐎电懓绨?ConversationSummaryDto閿?*/
export interface ConversationSummary {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messageCount: number
  createdAt: string
}

/** 鐎电鐦界拠锔藉剰閿涘牆顕惔?ConversationRecord閿?*/
export interface ConversationDetail {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messages: MessageRecord[]
  createdAt: string
  updatedAt: string
}

/** 濞戝牊浼呯拋鏉跨秿閿涘牆顕惔?MessageRecord閿?*/
export interface MessageRecord {
  id: string
  conversationId: string
  role: string
  content: string
  compressedContent: string | null
  compressionLevel: 'ORIGINAL' | 'SUMMARY' | 'KEYPOINTS' | 'ARCHIVED'
  isPinned: boolean
  toolCallJson: string | null
  tokenCount: number
  createdAt: string
}

/** 鐎电鐦介崚妤勩€冮弻銉嚄閸欏倹鏆?*/
export interface ConversationListParams {
  page?: number
  size?: number
  q?: string
  timeFrom?: string
  timeTo?: string
}

/** 閹垮秳缍斿Ο鈩冩緲閿涘牆顕惔?ProcedureTemplate閿?*/
export interface ProcedureTemplate {
  templateId: string
  name: string
  description: string
  triggerIntent: string
  steps: TemplateStep[]
  variables: Record<string, string>
  successRate: number
  useCount: number
  lastUsedAt: string | null
  sourceTraceIds: string[]
  createdAt: string
  updatedAt: string
}

/** 濡剝婢樺銉╊€?*/
export interface TemplateStep {
  stepIndex: number
  action: string
  toolName: string | null
  parameters: Record<string, unknown>
  expectedOutcome: string | null
}

/** 閸嬪繐銈界憴鍕灟閿涘牆顕惔?PreferenceRule閿?*/
export interface PreferenceRule {
  ruleId: string
  category: string
  key: string
  value: string
  confidence: number
  learnedFrom: string
  observationCount: number
  createdAt: string
  updatedAt: string
}

/** 濡剝婢橀崚妤勩€冮弻銉嚄閸欏倹鏆?*/
export interface TemplateListParams {
  page?: number
  size?: number
  q?: string
  sortBy?: string
  order?: string
}

/** 闁绻曢弮銉ョ箶閿涘牆顕惔?ForgettingLogDto閿?*/
export interface ForgettingLog {
  id: string
  entityId: string
  entityName: string
  strategy: string
  actionTaken: string
  forgettingPriority: number
  reason: string
  createdAt: string
}

/** 闁绻曢弮銉ョ箶閺屻儴顕楅崣鍌涙殶 */
export interface ForgettingLogListParams {
  page?: number
  size?: number
  timeFrom?: string
  timeTo?: string
  strategy?: string
}

/** 鐎圭偘缍嬬猾璇茬€烽弸姘閺勭姴鐨犻敍鍫㈡暏娴滃海鐡柅澶夌瑓閹峰顢嬮敍?*/
export const ENTITY_TYPES = [
  { value: 'PERSON', label: '人物' },
  { value: 'ORGANIZATION', label: '组织' },
  { value: 'PLACE', label: '地点' },
  { value: 'EVENT', label: '事件' },
  { value: 'PROJECT', label: '项目' },
  { value: 'TOPIC', label: '主题' },
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'HABIT', label: '习惯' },
  { value: 'GOAL', label: '目标' },
  { value: 'SKILL', label: '技能' },
  { value: 'CUSTOM', label: '自定义' },
] as const

// ========== Eval 鐠囧嫪鍙婂Ο鈥虫健缁鐎?==========

/** Benchmark 閸︾儤娅?*/
export interface BenchmarkScenario {
  id: string
  name: string
  userInput: string
  expectedToolCalls: string[]
  expectedOutputPattern?: string | null
  dimensionWeights: Record<string, number>
  timeoutSeconds: number
  mockToolResponses?: Record<string, string> | null
  mockTools?: MockToolSpec[] | null
  initialContext?: Record<string, string> | null
  tags: string[]
  llmJudgeCriteria?: string | null
  expectedTokenBudget: number
  expectedStepCount: number
  category?: string | null
  difficulty?: string | null
  description?: string | null
}

/** 閺呴缚鍏?Mock 瀹搞儱鍙跨€规矮绠?*/
export interface MockToolSpec {
  toolId: string
  behaviors: MockBehavior[]
  defaultResponse: string
}

/** Mock 鐞涘奔璐熺€规矮绠?*/
export interface MockBehavior {
  parameterPattern?: string | null
  response: string
  simulateError: boolean
  delayMs: number
}

/** 鐠囧嫪鍙婄紒鎾寸亯 */
export interface EvalResultItem {
  evalId: string
  traceId: string
  scenarioId: string
  dimensionScores: Record<string, number>
  overallScore: number
  violations: string[]
  suggestions: string[]
  llmJudgeScore?: number | null
  llmJudgeJustification?: string | null
  llmJudgeTokensUsed: number
  evaluatedAt: string
  gitCommitHash?: string | null
  gitBranch?: string | null
  evalRunId: string
  diagnosticJson?: string | null
  runMetadataJson?: string | null
}

/** 鐠囧﹥鏌囬幎銉ユ啞 */
export interface DiagnosticReport {
  dimensionDiagnostics: DimensionDiagnostic[]
  actionableSuggestions: string[]
  overallAssessment: string
}

/** 缂佹潙瀹崇拠濠冩焽 */
export interface DimensionDiagnostic {
  dimension: string
  label: string
  score: number
  diagnosis: string
  fixes: string[]
}

/** 鏉╂劘顢戦崗鍐╂殶閹?*/
export interface RunMetadata {
  modelId?: string | null
  promptVersion?: string | null
  configSnapshot?: string | null
  baselineRunId?: string | null
  labels: Record<string, string>
}

/** 鐠囧嫪鍙婇幎銉ユ啞濮瑰洦鈧?*/
export interface EvalReportSummary {
  evalRunId: string
  totalScenarios: number
  passCount: number
  failCount: number
  averageOverallScore: number
  dimensionAverages: Record<string, number>
  degraded: boolean
  regressedScenarios: string[]
  newRegressions: string[]
  evaluatedAt: string
  metadata?: RunMetadata | null
}

/** A/B 鐎佃鐦幎銉ユ啞 */
export interface ComparisonReport {
  currentRunId: string
  baselineRunId: string
  currentAvg: number
  baselineAvg: number
  delta: number
  scenarios: ScenarioComparison[]
  currentMetadata?: RunMetadata | null
  baselineMetadata?: RunMetadata | null
}

/** 閸︾儤娅欑痪褍顕В?*/
export interface ScenarioComparison {
  scenarioId: string
  currentScore: number
  baselineScore: number
  delta: number
  status: 'improved' | 'degraded' | 'unchanged' | 'new'
}

/** 鐠囧嫪鍙婇崣宥夘洯 */
export interface EvalFeedback {
  feedbackId: string
  evalId: string
  scenarioId: string
  feedbackType: 'AGREE' | 'DISAGREE' | 'GOLDEN_ANSWER'
  comment?: string | null
  goldenAnswer?: string | null
  createdBy?: string | null
  createdAt: string
}

/** 鐠囧嫪鍙婃潻鎰攽鐠囬攱鐪?*/
export interface EvalRunRequest {
  scenarioIds?: string[] | null
  tag?: string | null
  baselineRunId?: string | null
  labels?: Record<string, string> | null
  smokeTestOnly?: boolean | null
  offlineReeval?: boolean | null
}
