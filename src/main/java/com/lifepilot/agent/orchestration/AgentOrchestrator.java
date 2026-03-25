package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.DegradedResponseBuilder;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.checkpoint.AgentCheckpoint;
import com.lifepilot.agent.checkpoint.AgentCheckpointFingerprinter;
import com.lifepilot.agent.checkpoint.AgentCheckpointStore;
import com.lifepilot.agent.callback.StreamingCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.MediaValidationException;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Agent 闂備礁婀遍悷鎶藉幢閳哄倹鏉哥紓鍌氬€搁崐褰掓偋濡ゅ啯鏆滈柟鎹愵嚙闂傤垶鏌曟繝蹇曞矝闁?
 *
 * <p>闂佽崵濮甸崝妤呭窗閺囥垺鍎楁俊銈呮噹鐟欙箓鎮橀悙浣冩闁告柨瀚伴弻鏇㈠幢閺囩姴濡界紓浣风筏缁犳垿顢氶敐鍫㈢杸閻庯急鍐у婵炶揪绲块幊鎾绘儊椤栫偞鍋ｇ憸鏃堝绩鏉堛劎顩锋い鏃囨缁剁偟鈧箍鍎辩€氼噣鎮疯箛娑欑厸濠㈣泛妫岄崑鎾诲箵閹烘梹袙闂傚倷鑳堕崑鎾崇暦濮椻偓閹本銈ｉ崘鈺佸壄闂佸憡娲﹂崢鎯ь渻娴犲鐓欓柣鎴灻柌婊呯磼鏉堛劎绠樼紒杈ㄥ浮閹垽宕滄笟鍥︾礃濠电姰鍨煎▔娑氣偓姘煎櫍楠炲啯绻濋崟顒€鐝伴梻浣哥仢椤戝洤锕㈤弶鎳虫盯寮堕崹顕呬患濡炪倧鑵归弲鐘诲箖閸洖骞㈤柟鑸妼娴滄儳霉閿濆懏鎯堢粭鎴︽⒑鐠団€冲幐缂佲偓娓氣偓瀹曞綊宕归锝呭伎闁诲函缍嗘禍婊堫敋瑜旈弻?
 * checkpoint闂備線娼уΛ鏃€娼忓〒鐎塩e闂備線娼уΛ鏃堟嚄閸撲礁鍨濋柟鎹愵嚙缁犳垵霉閿濆牆袚闁稿簼鍗抽獮鏍偓娑櫳戠亸顐ょ磼?SSE 闂備浇銆€閸嬫捇鎮规ウ鎸庮仩闁哥喐鐓￠弻銊モ槈濞嗘垟妲堥梺鍛婄懄鐎笛囧箯閸涙潙绠婚柤鍝ユ暩閺?ReAct 闁诲海鏁婚崑濠冪閻愯櫣宓侀柛鈩冾焽椤╅鈧箍鍎遍幏瀣焽閵堝鐓欓柛顭戝亽閻掔晫绱?{@link ReactAgentLoop}闂?/p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";

    // ===== 闂備礁鎼粔鍫曗€﹂崼銏㈢处闁绘劦鍓涢悷瑙勭節闂堟冻鍔熸い?=====
    private final ReactAgentLoop agentLoop;
    private final StreamingEventHandler streamingEventHandler;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;
    private final GenerationRouter generationRouter;
    @Nullable private final TraceRecorder traceRecorder;
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaValidator mediaValidator;
    @Nullable private final MediaProcessor mediaProcessor;
    @Nullable private final AgentCheckpointStore checkpointStore;
    @Nullable private final SuspendStore suspendStore;
    private final AgentExecutionPersistenceSupport executionPersistence;

    public AgentOrchestrator(
            ReactAgentLoop agentLoop,
            AgentPersistenceHandler persistenceHandler,
            StreamingEventHandler streamingEventHandler,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            GenerationRouter generationRouter,
            @Nullable TraceRecorder traceRecorder,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaValidator mediaValidator,
            @Nullable MediaProcessor mediaProcessor,
            @Nullable AgentCheckpointStore checkpointStore,
            @Nullable SuspendStore suspendStore,
            @Nullable ChatTurnService chatTurnService) {
        this.agentLoop = agentLoop;
        this.streamingEventHandler = streamingEventHandler;
        this.config = config;
        this.objectMapper = objectMapper;
        this.generationRouter = generationRouter;
        this.traceRecorder = traceRecorder;
        this.multimodalRouter = multimodalRouter;
        this.mediaValidator = mediaValidator;
        this.mediaProcessor = mediaProcessor;
        this.checkpointStore = checkpointStore;
        this.suspendStore = suspendStore;
        this.executionPersistence = new AgentExecutionPersistenceSupport(persistenceHandler, chatTurnService);
    }

    /** 闂備礁鎲＄敮鍥磹閺嶎厼钃熼柛銉墮閸欏﹥銇勯弽銊ь暡闁稿骸锕ら埥澶愬箻鐟欏嫨鈧啫顭跨捄铏剐х€殿噮鍣ｅ鐢告偨閻㈢數宕堕梺鑽ゅТ濞诧妇鎹㈤幇顔筋潟婵犻潧娲ㄩ埢鏃傗偓骞垮劚濡鎯侀灏栨闁哄倹瀵ч崳褰掓倵?transcript 濠电偞鍨堕幐绋款潩閿曞偆鏁婇柛銉簽閻も偓闂佺硶鍓濋崝蹇涘磻?*/
    private boolean isTestSession(@Nullable String sessionId) {
        return executionPersistence.isTransientSession(sessionId);
    }

    // ===== 闂備礁鎲￠懝楣冨嫉椤掑嫷鏁嗛柣鎰惈缁犮儳鎲搁幋锔衡偓渚€骞嬮敃鈧粈鍌炴煏婢跺牆鍔氱紓?=====
    /**
     * 闂備礁鎲￠懝楣冨嫉椤掑嫷鏁嗛柣鎰惈缁犮儳鎲搁幋锔衡偓渚€骞嬮敃鈧粈鍌炴煏婢跺牆鍔氱紓宥嗘崌閺?
     *
     * <p>闂佽崵濮甸崝妤呭窗閺囥垺鍎楁俊銈勮兌閳绘棃鏌ら崗鍏兼瀯缂侇喗鎸搁埞鎴︻敍濞戞鐟愮紓鍌氱Т椤戝棝鏁嶉幇鏉跨妞ゆ劦鍋呰ⅸ闂備浇宕甸崑娑樜涙惔銊ョ劦妞ゆ垵鐏濋¨鍓嘺te 闂備礁鎲＄敮妤冩崲閸岀儑缍栭柟鐗堟緲缁€宀勬煛瀹ュ啫鍔楅柛瀣尰閹峰懘鎮滃Ο缁橆吂闂備浇顫夐幐鎶藉绩鏉堚晝鐭撻柣鎴ｆ缁犳帗銇勯弽銊х畺闁稿簼鍗抽獮鏍偓娑櫳戠亸顏堟煃瑜滈崗娑欐綇濞撶€塩e闂備線娼уΛ鏃堟⒓濞撳挜ct 闁诲海鏁婚崑濠冪閻愯櫣宓侀柛鈩冪☉缁犮儳鎲搁幋锔衡偓渚€骞嬮悙娈挎祫闂侀潧顦崕閬嶅箰閵堝鐓涢柛顐ｈ壘娴滃墽绱?turn 闂備浇銆€閸嬫捇鎮规ウ鎸庮仩闁哥喐鐓￠弻?
     */
    public AgentResponse run(AgentRequest request) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        var loopContext = new AgentLoopContext();

        var token = new CancellationToken();
        final AgentRequest effectiveRequest = preprocessMedia(request, state, null, null);
        if (effectiveRequest == null) {
            // preprocessMedia 闂佸搫顦弲婊堝蓟閵娿儍?null 闂佽崵鍋炵粙蹇涘礉鎼淬劌桅婵﹩鍘奸崹鏃堟煙閸濆嫮肖缂佸銈搁弻鈥愁吋韫囨洜鐦堥梺缁樼⊕閻燂箑顕ラ崟顒佺秶妞ゆ劑鍎涢弴銏＄叆婵炴垶锚椤ㄦ瑧绱掗濂割€楅棁澶娒归敐鍛喐缁绢厸鍋撴繝纰樻閸亪鍩€椤掆偓绾绢厾娑甸埀顒勬⒑缁嬭法绠ｆ繛鏉戞喘椤㈡瑨绠涢弴鐔风毇婵炲鍘ч悺銊╊敁閺嶎偆纾奸柣娆愮懃閸燁偊鎮楁繝姘厸闁?
            return AgentResponse.error(state, new MediaValidationException("\u5a92\u4f53\u6821\u9a8c\u5931\u8d25"));
        }

        try {
            state = initStateWithResumePolicy(effectiveRequest);
            executionPersistence.bindTurnTrace(state);
            boolean testSession = isTestSession(effectiveRequest.sessionId());
            executionPersistence.persistUserTurn(state, effectiveRequest);
            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();
            var callback = new com.lifepilot.agent.callback.NonStreamingCallback(
                    config, generationRouter, multimodalRouter, effectiveRequest, agentLoop);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, token, loopContext);
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                return handleSuspendSync(state, traceContext, loopContext);
            }
            if (state.terminationReason() == null) {
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder().reasoningSummary(summary).build();
                clearCheckpoint(effectiveRequest);
            } else {
                saveCheckpoint(state, effectiveRequest);
            }

            String assistantEntryId = null;
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
            }
            executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));

            TokenUsage tokenUsage = aggregateTokenUsage(traceContext);
            var cachedTree = loopContext.getLastCollectedA2uiTree();
            var a2uiComponents = cachedTree != null ? cachedTree.components() : null;

            return buildAgentResponse(state, assistantEntryId, a2uiComponents, tokenUsage);

        } catch (Exception e) {
            log.error("ReAct 闁诲海鏁婚崑濠冪閻愯櫣宓侀柛鈩冾殢閸ゆ洟鏌涚仦鐐殤闁糕晜顨堢槐鎾存媴閸濄儱顤€濡? error={}", e.getMessage(), e);
            if (shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, e);
                saveCheckpoint(state, effectiveRequest);
                boolean testSession = isTestSession(effectiveRequest.sessionId());
                String assistantEntryId = null;
                if (!testSession) {
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                return buildAgentResponse(state, assistantEntryId, null, aggregateTokenUsage(traceContext));
            }
            error = e;
            executionPersistence.markTurnFailed(state, e);
            return AgentResponse.error(state, e);
        } finally {
            endTraceIfEnabled(traceContext, state, error);
        }
    }
    /**
     * 婵犵數鍋熺换婵堢矆娴ｇ儤顫曢柨鐔哄Т缁犮儳鎲搁幋锔衡偓渚€骞嬮敃鈧粈鍌炴煏婢跺牆鍔氱紓宥嗘崌閺?
     *
     * <p>濠电偞鍨堕幐鍛婎殽閹间礁绠栭柟鐐墯濞间即鏌曟径鍫濆闁绘柨鎳愰埀顒€鐏氬妯尖偓姘煎墴瀹曪綁鍩勯崘顏嶆锤濡炪倖鍔﹀鎸庣閿濆鈷戞い鏂挎惈婵″ジ鏌涢妸銉︽崳缂佽鲸鎹囧浠嬪Ω閵壯呮噰闂傚倷绶￠崑鍛ｉ幒鏃€顐芥い鎰堕檮閺咁剟鎮橀悙璺轰汗缂佸顕ц灋闁绘鐗忕粻鎾淬亜閿曚礁鍚归柟宄扮秺閹垽鎳￠妶鍥у箑 traceStart闂備線娼уΛ鏃堟⒓濞ｆ弬SONING/DONE/ERROR 濠电偛鐡ㄧ划宀勵敄閸曨偀鏋庨柕蹇嬪€曞浠嬫倶閻愭彃鈷旈柕鍫閳ь剚顔栭崰鏍崲閹达箑鍑犻柛鎰靛枛鐎氬銇勯幒宥囧妽闁哄棭浜幃妯跨疀鎼粹€崇彑闂?
     */
    public void runStreaming(AgentRequest request, String streamId,
                             SseSessionManager sseManager,
                             CancellationToken cancellationToken) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        String finalContent = "";
        TokenUsage finalTokenUsage = null;
        String reasoningSummary = null;
        String tempTurnId = request.turnId() != null && !request.turnId().isBlank()
                ? request.turnId()
                : UUID.randomUUID().toString();
        String userEntryId = null;
        String assistantEntryId = null;
        boolean testSession = false;
        var loopContext = new AgentLoopContext(sseManager, streamId, tempTurnId);
        final AgentRequest effectiveRequest = preprocessMedia(request, state, sseManager, streamId);
        if (effectiveRequest == null) return;

        try {
            state = initStateWithResumePolicy(effectiveRequest);
            executionPersistence.bindTurnTrace(state);
            testSession = isTestSession(effectiveRequest.sessionId());
            userEntryId = executionPersistence.persistUserTurn(state, effectiveRequest);
            if (state.traceId() != null) {
                var traceStartData = new HashMap<String, Object>();
                traceStartData.put("sessionId", request.sessionId());
                traceStartData.put("turnId", tempTurnId);
                traceStartData.put("traceId", state.traceId());
                traceStartData.put("timestamp", Instant.now().toEpochMilli());
                if (userEntryId != null) {
                    traceStartData.put("userEntryId", userEntryId);
                }
                sseManager.sendEvent(streamId, SseEventType.TRACE_START, traceStartData);
            }

            agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                    "AGENT_START", "\u5f00\u59cb\u6267\u884c",
                    "Agent \u5f00\u59cb\u6267\u884c\u4efb\u52a1\uff0c\u6b63\u5728\u521d\u59cb\u5316\u63a8\u7406\u5faa\u73af\u548c\u6d41\u5f0f\u8f93\u51fa\u3002",
                    null, Map.of());

            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();
            var callback = new StreamingCallback(config, generationRouter, multimodalRouter, agentLoop,
                    cancellationToken, null, sseManager, streamId,
                    request.sessionId(), tempTurnId, effectiveRequest);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, cancellationToken, loopContext);
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                handleSuspendStreaming(state, streamId, sseManager, loopContext);
                return;
            }

            // 濠电姰鍨煎▔娑氣偓姘煎櫍楠炲啯绻濋崑顖濐潐閹峰懘宕妷褜鏀ㄩ梻浣规偠閸庢娊宕戦悩缁樺剭闁绘柨鍚嬮悡銉︾箾閸℃ê淇柛?
            if (callback.hasStreamingError()) {
                Exception streamingFailure = callback.getStreamingError();
                if (shouldDegradeUnexpectedException(state)) {
                    state = degradeForException(state, streamingFailure);
                    finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                    saveCheckpoint(state, effectiveRequest);
                } else {
                    error = streamingFailure;
                }
            }

            if (error == null) {
                // 濠电偞娼欓崥瀣晪闂佸憡蓱缁嬫帞绮氶崡鐐╂斀闁割偆鍠愰悾顒勬倵鐟欏嫮鎽冮悘蹇旂懅閸掓帡鍨剧搾浣筋潐閹峰懐绮欓幐搴ｆ闂備焦鐪归崝宀€鈧凹鍓熷畷顒傗偓鐢电《閸嬫捇宕烽鐕佷户缂備浇椴哥换鍫ュ蓟閸℃稑鍨傛い鏃傚帶鐠у绱撻崒娆戭槮闁绘锕崺鈧い鎴炲缁佺増銇勯埡濠備喊闁诡垰瀚板鍊燁槻妞ゃ倕鎳樺濠氬醇濞戞浠奸梺鍛婂煀缁绘繂顕ｉ崹顐㈢窞妤犵儐鍠楃敮锟犲蓟鐏炲墽绡€闁告劏鏅濋埀顒夊櫍閺?
                String callbackContent = callback.getFinalContent();
                finalContent = state.finalOutput() != null
                        ? state.finalOutput()
                        : (callbackContent != null ? callbackContent : finalContent);
                var extractedFinalContent = streamingEventHandler.extractA2uiContent(finalContent);
                A2uiComponentTree finalA2uiTree = extractedFinalContent.tree();
                finalContent = extractedFinalContent.visibleText();
                if (finalA2uiTree != null) {
                    loopContext.setLastCollectedA2uiTree(finalA2uiTree);
                }

                if (state.terminationReason() == null) {
                    reasoningSummary = buildReasoningSummary(state, traceContext);
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .reasoningSummary(reasoningSummary)
                            .build();
                    clearCheckpoint(effectiveRequest);
                } else {
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .build();
                    saveCheckpoint(state, effectiveRequest);
                }

                // 闂備礁缍婇弲鎻掝渻閹烘梻涓嶆繛鍡樻尭缁€宀勬煛瀹ュ啫濡藉ù鐘愁焽缁辨挻鎷呴崫銉ヮ暫婵犳鍣禍顏堢嵁瀹ュ洠鍋撻敐搴″箻缂傚牆顭烽弻?
                if (!testSession) {
                    String a2uiJson = streamingEventHandler.serializeA2uiTree(
                            loopContext.getLastCollectedA2uiTree());
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantStreaming(
                            state, finalContent, reasoningSummary, a2uiJson, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                finalTokenUsage = aggregateTokenUsage(traceContext);
            }
        } catch (Exception e) {
            log.error("婵犵數鍋熺换婵堢矆娴ｇ儤顫?Agent 闂備礁婀遍悷鎶藉幢閳哄倹鏉搁柣搴㈩問閸犳牠宕愰幖浣瑰亯闁告挷鑳剁壕濂告煕椤愩倕娅忛柛? error={}", e.getMessage(), e);
            if (shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, e);
                finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                saveCheckpoint(state, effectiveRequest);
                if (!testSession) {
                    String a2uiJson = streamingEventHandler.serializeA2uiTree(
                            loopContext.getLastCollectedA2uiTree());
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantStreaming(
                            state, finalContent, reasoningSummary, a2uiJson, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                finalTokenUsage = aggregateTokenUsage(traceContext);
            } else {
                error = e;
            }
        } finally {
            endTraceIfEnabled(traceContext, state, error);

            if (error != null) {
                executionPersistence.markTurnFailed(state, error);
                streamingEventHandler.sendStreamError(
                        sseManager,
                        streamId,
                        500,
                        "濠电姰鍨煎▔娑氣偓姘煎櫍楠炲啯绻濋崟顒€鐝伴梻浣哥仢椤戝洤锕㈤幘顔界厸闁告劦鍘界涵鍓ф喐閺夊灝鈧潡骞冩禒瀣亜鐎瑰嫮澧楅弳銉╂⒒娓氬洤鏋旈柛鏃€鍨垮顐ｇ節閸曨剙鐝? " + error.getMessage(),
                        state.traceId(),
                        tempTurnId,
                        ChatTurnStatus.FAILED
                );
            } else {
                agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                        "ANSWER_FINALIZED", "\u56de\u7b54\u5df2\u5b8c\u6210",
                        "\u6d41\u5f0f\u8f93\u51fa\u5df2\u5b8c\u6210\uff0c\u6b63\u5728\u53d1\u9001 DONE \u4e8b\u4ef6\u3002",
                        null, Map.of());
                var doneData = streamingEventHandler.buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        state.steps(), reasoningSummary, finalContent, assistantEntryId,
                        loopContext.getLastCollectedA2uiTree());
                sseManager.sendEvent(streamId, SseEventType.DONE, doneData);
                sseManager.closeEmitter(streamId);
            }
        }
    }
    /**
     * 闂備浇顕栭崢褰掑垂瑜版崵鍥箵閹烘繂鏅犻梺鍛婄懃椤︻垶鎯侀鐐村仯鐟滄棃藟閹炬枼鏋?Agent闂?
     *
     * <p>闂佸搫顦弲婊堟偡閳哄懎闂柣鎴ｆ缁€鍌炴煕椤愶絿鐭岄柣?suspendStore 闂備浇顕栭崢褰掑垂瑜版崵?state闂備焦瀵х粙鎴︽嚐椤栫偛鐤柍褜鍓熼幃璺衡槈閺嵮冨缂備焦姊归幐鍐差嚕?Resume/Observation 婵犳鍠楃缓鍧楀磿閻㈠壊鏁侀柛鎰靛枟閺咁剟鎮橀悙闈涗壕濞寸姵锕㈤弻娑橆潩椤掑倸鍤┑顔斤公婵″洨妲愰幒鎴旀瀻闊洦鏌ㄩ埀顒傛暬閺屾稑鈻庨幇顒夋闂佹悶鍊曞ù椋庡垝濞嗘垟鍋撻敐搴″闁糕晜绋撶槐鎾寸瑹閸パ冪闁汇埄鍨奸崑濠傤嚕閸洖唯鐟滃酣鎮?ReAct 闁诲海鏁婚崑濠冪閻愯櫣宓侀柛鈩冪☉杩?
     */
    public void resumeFromSuspend(String traceId, ResumePayload payload) {
        if (suspendStore == null) {
            throw new IllegalStateException("SuspendStore 闂備礁鎼悧婊勭濠靛鏋侀柣鎰惈缁€鍌炴煏婢舵ê鐏ｇ紒鈧径鎰厸闁告劦浜滄牎缂佺虎鍘奸悥濂哥嵁娓氣偓婵偓闁绘瑢鍋撴俊鎻掝煼閺岀喓绮欓崹顔瑰亾娴犲绠?Agent");
        }
        SuspendedAgent suspended = suspendStore.load(traceId)
                .orElseThrow(() -> new IllegalStateException("闂備礁鎼悧婊勭濠靛洨鐝舵慨妞诲亾鐎规洘宀搁幃褔宕煎┑鍫熜﹂梺?Agent: " + traceId));
        agentLoop.validateResumePayload(suspended.suspendReason(), payload);

        ReactAgentState state = suspended.toAgentState(objectMapper).resume();
        state = state.appendStep(new ReactStep.Resume(payload, Instant.now(),
                Duration.between(suspended.suspendedAt(), Instant.now())));
        String resumeToolId = "resume:" + suspended.suspendReason().getClass().getSimpleName();
        state = state.appendStep(new ReactStep.Observation(
                resumeToolId, null, true, agentLoop.formatResumeObservation(payload), 0));
        suspendStore.delete(traceId);

        log.info("Agent 闂備浇顕栭崢褰掑垂瑜版崵鍥嚑椤掍礁鐝伴梻浣哥仢椤戝洤锕㈢€电硶鍋撻崷顓х劸鐎殿喖澧庨弫顕€骞橀鑲╁摋? traceId={}, reasonType={}, payloadType={}",
                traceId, suspended.suspendReason().getClass().getSimpleName(),
                payload.getClass().getSimpleName());

        final ReactAgentState resumedState = state;
        Thread.startVirtualThread(() -> runResume(resumedState));
    }

    /** 闂備線娼荤拹鐔煎礉瀹€鍕畺婵°倕鎳庨惌妤呮煙濞堝灝鏋涙繛鍫㈠Х缁辨帞鈧綆浜堕崕搴ㄦ煠閸偄鐏╃紒杈ㄥ笩椤︽挳鏌ｉ姀鐙€鐓兼鐐茬Х椤﹁埖銇勯姀鈥崇伌妤犵偞鍔欏畷鎺戔攽閸パ囨７闂?Agent闂?*/
    /** 闂備線娼荤拹鐔煎礉瀹€鍕畺婵°倕鎳庨惌妤呮煙濞堝灝鏋涙繛鍫㈠Х缁辨帞鈧綆浜堕崕搴ㄦ煠閸偄鐏ユい鏇熺懇瀹曟粍鎷呴悜妯绘瘞闂備礁婀遍…鍫ュ磹娴犲绠查柨婵嗘椤╃兘鎮归崶銊ョ祷妞ゎ偁鍊濋弻銊モ槈濡偐鍔繛鏉戠毞閺呯姴鐣峰Δ鈧鍏煎緞鐎Ｑ勭€诲┑鐘绘涧椤﹂亶宕戝☉姘辩閻庯綆鍠栫粈?Web 闂佽崵濮村ú顓㈠绩闁秵鍎戝ù鍏兼綑杩?*/
    private void runResume(ReactAgentState state) {
        var token = new CancellationToken();
        var loopStart = Instant.now();
        var loopContext = new AgentLoopContext();

        try {
            var request = new AgentRequest(
                    state.goal(),
                    state.sessionId(),
                    state.channel(),
                    state.userId(),
                    state.turnId(),
                    ChatTurnAction.RESUME,
                    state.taskMode(),
                    null,
                    state.budget(),
                    state.parentTraceId(),
                    state.depth(),
                    state.preferredProvider(),
                    state.allowedToolIds(),
                    null,
                    null,
                    ResumePolicy.AUTO
            );
            var callback = new com.lifepilot.agent.callback.NonStreamingCallback(
                    config, generationRouter, multimodalRouter, request, agentLoop);
            state = agentLoop.coreLoop(state, request, null, loopStart, callback, token, loopContext);

            boolean testSession = isTestSession(state.sessionId());
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                String assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
            }
            log.info("Agent 闂備浇顕栭崢褰掑垂瑜版崵鍥蓟閵夈儳顓奸悷婊勫灴閵嗕線骞嬮悩顐壕闁荤喓澧楀﹢浼存煕? traceId={}, stepCount={}", state.traceId(), state.stepCount());
        } catch (Exception e) {
            executionPersistence.markTurnFailed(state, e);
            log.error("Agent 闂備浇顕栭崢褰掑垂瑜版崵鍥蓟閵夈儳顓奸悷婊勫灴閵嗕線骞嬮悙鑼獮闁哄鐗滈崑澶庮杺: traceId={}, error={}", state.traceId(), e.getMessage(), e);
        }
    }
    /**
     * 闂佽娴烽弫鎼併€佹繝鍥舵晪妞ゆ巻鍋撻弫鍫ユ煕鐏炲墽鎳嗛柛姗嗗墴閺岋綁濡搁妷銉患闂佺粯绋撻崰鎰板箯閸涙潙绠婚柛蹇撴噽妞规娊姊洪崫鍕枌缂佺粯绻堥幃鏉库枎閹捐櫕銇濋悗鍏夊亾闁告洖鐏氬▓銊︾節閵忥絾纭鹃悗姘煎櫍楠炲啯绻濋崶顬?
     *
     * <p>闂備礁鎲￠懝楣冨嫉椤掑嫷鏁嗛柣鎰仛瀹曞銇勯弽銊︾殤缂佹劗鍋涢湁闁稿繐鍚嬮惃鎴炵箾閹绘帗鍋ユ鐐村浮婵＄兘鏁冮埀顒勶綖閺冨牊鐓曢柟瀵稿Т閸斿鏌ｅΔ鈧€涒晠銆冮妷褌娌柣锝呯灱閿涙繈鏌ｉ姀鈺佺仸婵炶壈宕靛Σ鎰板础閻忔槒顫夐幏鍛村传閵壯屾敤闂佽崵濮崇拃锕傚垂閹殿喗顐介柣鎰惈缁€鍡樼箾閸℃ê绗氱紒渚囧櫍閺?ERROR 濠电偛鐡ㄧ划宀勵敄閸曨偀鏋庨柕蹇嬪€楀畵渚€鏌￠崼婊呯シ闁告﹩鍓熼弻锟犲磼閵堝懎绠圭紓鍌氱Т椤戝鐣峰鍐惧悑闁割偒鍋勫▓鎻掆攽椤旂偓鍤€闁稿﹤鐖奸崺鈧?
     */
    @Nullable
    private AgentRequest preprocessMedia(AgentRequest request, ReactAgentState state,
                                         @Nullable SseSessionManager sseManager,
                                         @Nullable String streamId) {
        if (!hasMultimodalContent(request)) return request;

        if (multimodalRouter == null) {
            log.warn("闂佽崵濮村ú顓㈠绩闁秵鍎戝ù鍏兼綑缁€宀勬煕濞戝崬骞楅柛搴㈡尭閳规垿顢涘☉妯肩憪缂傚倸绉撮澶婄暦濮椻偓瀹曘劑顢樿閺咃絾绻?MultimodalRouter 闂備礁鎼悧婊勭濠靛鏋侀柣鎰惈缁€鍌炴煏婢舵ê鐏ｇ紒鈧径鎰拻闁告洦鍋嗚倴婵°倗濮村ú锔剧矙婢舵劖鎯為柣鐔告緲濮ｆ劙姊洪崫鍕偓绋棵洪敃鈧敃? sessionId={}",
                    request.sessionId());
            return request;
        }

        if (mediaValidator == null || mediaProcessor == null) return request;

        try {
            var processedMedia = validateAndPreprocessMedia(request.mediaContents());
            return new AgentRequest(
                    request.message(),
                    request.sessionId(),
                    request.channel(),
                    request.userId(),
                    request.turnId(),
                    request.action(),
                    request.taskMode(),
                    request.systemPrompt(),
                    request.budget(),
                    request.parentTraceId(),
                    request.depth(),
                    request.preferredProvider(),
                    request.allowedToolIds(),
                    processedMedia,
                    request.temperature(),
                    request.resumePolicy()
            );
        } catch (MediaValidationException e) {
            log.warn("濠电姵顔栭崹杈╂暜婵犲嫮绀婇悗锝庡枛閸愨偓闂佽法鍠撴慨宄扮暦閿濆拋鐔嗛柟顖涘缁ㄥ潡鎮? sessionId={}, error={}", request.sessionId(), e.getMessage());
            if (sseManager != null && streamId != null) {
                streamingEventHandler.sendStreamError(
                        sseManager,
                        streamId,
                        400,
                        "濠电姵顔栭崹杈╂暜婵犲嫮绀婇悗锝庡枛閸愨偓闂佽法鍠撴慨宄扮暦閿濆拋鐔嗛柟顖涘缁ㄥ潡鎮? " + e.getMessage(),
                        state.traceId(),
                        request.turnId(),
                        ChatTurnStatus.FAILED
                );
                return null;
            }
            throw e;
        }
    }
    /** 闂備礁鎲＄敮鍥磹閺嶎厼钃熼柛銉㈡櫆鐎氭岸姊洪崹顕呭剳婵犫偓閹绢喗鐓涢柛鏇㈡涧閻忕娀鏌熼娆掑厡闁逞屽墮濠€閬嶅磻閵堝拋鐎舵い鏍仜缁犲綊鏌涘┑鍡楊伀闁轰降鍔岄湁婵犲﹤鍟ˉ婊勩亜閿曗偓閻忔繆鐏掗梺鏂ユ櫅閸熺娀宕戦幘瀛樺闁告縿鍎遍悿顔界箾鏉堝墽绋婚柟绋跨埣閸┾偓?*/
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }
    /** 缂傚倸鍊烽懗鍫曞窗瀹ュ洨鍗氶柟缁㈠枛绾剧粯鎱ㄥ鍡楀鐎规洦鍨伴湁闁挎繂妫欑亸浼存煟濡も偓鐎涒晠銆冮妷褌娌柣鎴旀櫅娴滄儳霉閿濆懏鍟炵紒鍌氱墦閺岋絽螖閳ь剙煤椤擃潿鈧倿鍩￠崒姘辩獮闂佸憡娲﹂崢浠嬪磹閻愮儤鐓ユ繛鎴烆焾鐎氫即鏌ら懡銈呮瀾缂佸苯宕埞鎴﹀醇閻斿摜鏆氶梻鍌氬€搁悧蹇涘磻濞戞◤鐟邦潨閳ь剟骞冭瀹曞爼顢楁笟鍥ｅ亾瀹ュ悿褰掓偐閻戞銆愰柣銏╁灱閸嬪﹤顕ｆ禒瀣€婚悷娆欑到娴滈箖鐓崶褎鎹ｉ柣鎰躬閺?*/
    private List<MediaContent> validateAndPreprocessMedia(List<MediaContent> mediaContents) {
        assert mediaValidator != null;
        mediaValidator.validateAll(mediaContents);
        List<MediaContent> images = mediaContents.stream()
                .filter(mc -> mc.mimeType().startsWith("image/")).toList();
        assert mediaProcessor != null;
        List<MediaContent> processedImages = mediaProcessor.processAll(images);
        List<MediaContent> nonImages = mediaContents.stream()
                .filter(mc -> !mc.mimeType().startsWith("image/")).toList();
        var result = new ArrayList<MediaContent>(processedImages.size() + nonImages.size());
        result.addAll(processedImages);
        result.addAll(nonImages);
        return List.copyOf(result);
    }
    /**
     * 闂備礁鎲＄敮妤冩崲閸岀儑缍栭柟鐗堟緲缁€宀勬煛瀹ュ啫濡奸柍閿嬫閹?state闂備焦瀵х粙鎴︽嚐椤栫偞鍤愰柣鏃傚帶閹瑰爼鏌曟繛鍨姎閻㈩垱甯￠幃瑙勬媴閸涘﹤鏆堝┑锛勫仜閸婄粯绂嶇粙搴撴斀濠电姴瀚瑧濠?checkpoint 闂備浇顕栭崢褰掑垂瑜版崵鍥蓟閵夈儙?
     *
     * <p>婵犵數鍋炲娆擃敄閸儲鍎?闂佽崵濮村ú銈団偓姘煎幘缁﹦鈧稒锚椤曡鲸鎱ㄥΟ铏癸紞婵☆垰鐗嗛埥澶愬箻瀹曞泦锝夋偨椤栨稒灏︾€?checkpoint闂備焦瀵х粙鎴炵附閺冨倹宕叉慨妯块哺鐎氭艾霉閿濆懏璐℃俊灞傚妽缁绘盯宕辫箛鎾斥拤婵犫拃鍛珪闁诡喗婢橀悾婵嬪礋椤愩垹绠ｉ梻浣告惈鐎氱兘宕规导鏉戠闂侇剙绉寸粈鍕煃瑜滈崜鐔煎箖閳哄懏鍤嬬痪鐗埫禍鎯归敐鍥舵毌闁?
     */
    private ReactAgentState initState(AgentRequest request) {
        var defaultBudget = Budget.fromConfig(config.getBudget());
        if (isTestSession(request.sessionId())) {
            return ReactAgentState.init(request, defaultBudget);
        }
        var checkpoint = claimCheckpoint(request);
        if (checkpoint.isPresent()) {
            var restoredState = checkpoint.get().restore(objectMapper, request);
            log.info("闂備礁鎲￠懝鐐附閺冨牆绠查柕蹇嬪€曠粈澶愭煃閳轰礁鏋ゆ慨濠囩畺閺岋紕浠︾拠鎻掑Б闂佺顑戠紞渚€鐛笟鈧慨鈧柣娆屽亾婵℃彃顭烽弻鐔衡偓娑櫭慨鍥р攽? sessionId={}, fromTraceId={}, toTraceId={}",
                    restoredState.sessionId(), checkpoint.get().sourceTraceId(), restoredState.traceId());
            return restoredState;
        }
        return ReactAgentState.init(request, defaultBudget);
    }
    /** 闂備礁鎼粔鐑斤綖婢跺﹦鏆?resumePolicy 闂備礁鎲￠崝鏇㈠疮閸ф鍋╁Δ锝呭暙閸欏﹥銇勯弽銊ь暡妞ゆ劗鏅槐鎾寸瑹閸パ冪濠碘槅鍋掗崑鍕亱闂侀€炲苯澧寸€殿喖顭锋俊鐤槻濞寸媭鍨堕弻銊モ槈濡偐鍔紓浣虹帛濞茬喎顕ｆ导鎼晬婵炴垶鐟㈤弸鏍倵濞堝灝鏋涚紒璇差儏閳藉顢旈崟闈涙闂佸憡鍔戦崝宥夊磹閻㈠憡鈷戦柟缁樺笧鏍￠梺鍝勮嫰閿曘倝顢氶敐澶婄劦妞ゆ帊鐒﹂崣蹇涙倵閿濆啫濡烽柛?*/
    private ReactAgentState initStateWithResumePolicy(AgentRequest request) {
        if (request.resumePolicy() == ResumePolicy.FRESH) {
            clearCheckpoint(request);
            return ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        }
        return initState(request);
    }

    /** 闂備線娼荤拹鐔煎礉瀹€鍕畾閹兼番鍔嶉崑?trace 闂備礁鎼崯鍐测枖濞戙垹鍨傞柛妤冨剱閸ゅ牓寮堕悙鏉戭棆妞ゅ繘浜堕弻鈩冨緞閸℃鈷夐梺鍝勮嫰閿曨亪骞嗛崘顔肩妞ゆ巻鍋撻柍閿嬫閹泛鈽夊Ο鑲╁姰閻庣偣鍊楅崕銈咁焽婵犳氨宓侀幖瀛樻尭娴?*/
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    /** 缂傚倸鍊烽懗鍫曞窗瀹ュ洨鍗氶悗闈涙憸绾惧ジ鏌熼幆褏鎽犻悘?trace闂備焦瀵х粙鎴︽嚐椤栫偞鍤愰柣鏃傚劋閸嬫﹢鏌曟径鍡樻珖缂佸鍏橀弻鐔衡偓娑櫭慨鍥р攽?濠电姰鍨洪崕鑲╁垝閸撗勫枂闁挎梻鏅埢鏃堝箹鏉堝墽鎮奸柣顓熷笚閹便劌鈹戦崟顐や患闁汇埄鍨奸崑濠傜暦閸洘鍋愭い鏃傛嚀娴?*/
    private void endTraceIfEnabled(@Nullable TraceContext traceContext,
                                   ReactAgentState state, @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) return;
        String finalOutput = state.finalOutput();
        boolean success = error == null && state.terminationReason() == null;
        String errorMessage = error != null ? error.getMessage() : null;
        String terminationReason = error != null
                ? error.getClass().getSimpleName() : state.terminationReason();
        traceRecorder.endTrace(traceContext, finalOutput, success, errorMessage, terminationReason);
    }
    /** 闂佽绻愮换鎴犳崲閸℃稒鍎婃い鏍ㄧ矋婵ジ鏌曢崼婵愭▓闁哄棙鐟ラ埥澶愬箻閹剁瓔鈧绱撳鍜佸剶鐎规洘绮岄濂稿醇椤愩垺顔夋繝娈垮枤閹虫捇宕愰幖浣哥畺?fingerprint 闂備焦鐪归崝宀€鈧凹鍙冭棢闁告稒娼欓拑鐔兼煏婢诡垰鍟╅崠鏍⒑閹稿海鈽夐柣顒€銈稿顐﹀Χ閸℃瑯娲搁柟鍏肩暘閸婃洖鈻撴导瀛樼厱闁哄倽顕ф俊鍧楁煟閵忥絽鍚归柟鐟板楠炲鈻庣仦鎴掑?*/
    private Optional<AgentCheckpoint> claimCheckpoint(AgentRequest request) {
        if (!checkpointEnabled()) {
            return Optional.empty();
        }
        try {
            return checkpointStore.claim(request.sessionId(), request.channel(), fingerprintOf(request));
        } catch (Exception e) {
            log.warn("闂佽崵濮抽梽宥夊磹濠靛鈧?Agent 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑Б闂佺顑戠徊鎯ь嚗閸曨剚缍囨い鎰╁剾? sessionId={}, error={}", request.sessionId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 闂備線娼荤拹鐔煎礉閹存繍鐎跺瀣捣濡垳鎲歌箛鏇炲灊闁靛ň鏅涚痪褔鏌ｉ弬鎸庡暈妞は佸叇搴ㄥ炊閵娧呯暤闂佸鏉垮閾荤偤鏌嶈閸撶喎顕ｆ繝姘ㄧ憸宥嗙椤栫偞鐓ユ繛鎴灻〃娆戠磼閸撲焦鏆€规洏鍎查幆鏃堟晲閸ャ劍姣庣紓鍌氬€风紞鈧柛娑卞灡閺嗕即姊洪崷顓犳嚄闁糕檧鏅滈弬鈧梻浣烘嚀閻°劑鎮ч悩璇查棷缂佸顑欓崵鏇㈡煃瑜滈崜姘跺箯閸涱垱宕夐柕濠忛檮濞堛垽姊?*/
    private void saveCheckpoint(ReactAgentState state, AgentRequest request) {
        if (!checkpointEnabled() || !shouldPersistCheckpoint(state)) {
            return;
        }
        try {
            checkpointStore.save(AgentCheckpoint.from(state, fingerprintOf(request), objectMapper));
        } catch (Exception e) {
            log.warn("濠电儑绲藉ú锔炬崲閸岀偞鍋?Agent 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑Б闂佺顑戠徊鎯ь嚗閸曨剚缍囨い鎰╁剾? sessionId={}, traceId={}, error={}",
                    state.sessionId(), state.traceId(), e.getMessage());
        }
    }

    /** 闂備線娼荤拹鐔煎礉瀹ュ鏁嗘繝濠傛噺閺嗘粎绱掔€ｎ厽纭堕柡鍡樺哺閺岀喓鈧稒锚婵倿鏌嶈閸忔盯鎮為敂閿亾閸偅绀堥柟绉嗗洦鐓ラ悗锝庝簽娴犳劙姊洪崫鍕仾闁稿骸纾Σ?fresh 闂傚倷鐒﹁ぐ鍐矓鐎靛憡顫曢柟杈剧畱缁秹鏌ら崫銉︽毄妞ゅ繑鎮傞弻锝夋倷閸欏妫″┑鈽嗗亽閸嬪嫯鐏嬮梺閫炲苯澧寸€殿喖顭锋俊鐤槻濞寸媭鍨堕弻?*/
    private void clearCheckpoint(AgentRequest request) {
        if (!checkpointEnabled()) {
            return;
        }
        try {
            checkpointStore.delete(request.sessionId(), request.channel(), fingerprintOf(request));
        } catch (Exception e) {
            log.warn("婵犵數鍋為幐鎼佸箠閹版澘绠?Agent 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑Б闂佺顑戠徊鎯ь嚗閸曨剚缍囨い鎰╁剾? sessionId={}, error={}", request.sessionId(), e.getMessage());
        }
    }

    /** 闂備礁鎲￠悷顖涚濠靛棴鑰垮〒姘ｅ亾鐎规洜鍏樻俊鎼佸Ψ閵夈儲顓肩紓鍌氬€风粈浣烘崲鐎ｎ偒娈介柛銉墯閸嬨劑鏌ｉ弮鍥у惞缂佺姵鐗犻弻鐔虹箔濞戞ɑ锛嶉柡鈧禒瀣厽闁靛闄勭粈澶愭煙閼告娈滅€殿噮鍠氶幏瀣捶椤撶媭妲烽梻浣哥秺濞佳呯矓閸偂绻嗙紒瀣儥閸ゆ洟鏌涚仦鐐殤闁糕晜顨婂鍫曟倻閸℃顫梺鎼炲妽瀹€绋款潖閽樺鍚嬮柛銉墰瀹曟粎绱撻崒娆戝妽闁圭顭烽幃妯诲緞閹邦厼娈岄柣蹇曞仩閸嬫劙鎮峰┑瀣厸闁告洟娼ч悘锝嗙箾閹绘帗鍋ユ鐐村浮婵＄兘濡烽敐鍕線闂佽崵濮甸崝鎴﹀磿椤栫偛鐒?*/
    private boolean shouldDegradeUnexpectedException(ReactAgentState state) {
        return state.stepCount() > 0 || !state.steps().isEmpty();
    }

    /** 闂佽绻愮换鎰涘▎蹇ヨ€挎い鎾跺櫐缁憋綁鏌涢弴銊ュ闁糕晛鍊婚埀顒侇問閸犳牠宕愰幖浣瑰亯闁割偅娲栫粈宀勬煕濞戙垹浜版俊顐㈡噹閳藉骞橀搹顐ｅ創濡炪倖娲﹂崳锝夊箖娴犲惟闁挎洍鍋撻柣鎾存礋閺屾稑鈻庡Ο铏逛淮濡炪値鍋勫Λ婵嬪箚閸愵喖绀嬫い鏍ㄤ緱閳ь剚鐗滅槐鎺楁偑濞嗗繑鍣介柣顓熷浮閺岀喖顢栭崸妤侇€栭梺?*/
    private ReactAgentState degradeForException(ReactAgentState state, Exception e) {
        String reason = "闂備礁鎲￠悷锕傚垂瑜版帒鏋侀柟鎹愵嚙鐎氬銇勮箛鎾跺⒈闁哄棙娲熼弻锟犲醇閵忕姵鐎荤紓浣介哺閹搁箖寮? " + e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            reason += " - " + e.getMessage();
        }
        return DegradedResponseBuilder.terminateWithReason(state, reason);
    }

    /** 濠电偛顕慨鎾箠鎼粹槄鑰挎い蹇撶墛閳锋梻鈧箍鍎卞ú顓㈡偘閹炬枼妲堥柟鎯х摠閻撱儵鏌ｆ幊閸旀垵鐣烽悜钘壩╅柕澶堝劥椤斿姊虹拠鈥冲箲闁搞劌缍婅棟闁告瑥顦遍埢鏂库攽閻樿精鍏岄柣鐔告崌閺岋繝宕掑顓烆槱濠碉紕鍋涢崐鍧楃嵁瀹ュ拋鍚嬮柛鏇ㄥ幘椤旀棃鏌ｆ惔銏⑩姇閽冭鲸銇勯妷銉﹀殗鐎殿喖顭锋俊鐤槻濞寸媭鍨堕弻?*/
    private boolean shouldPersistCheckpoint(ReactAgentState state) {
        return state.completionMode() == CompletionMode.DEGRADED
                && state.terminationReason() != null
                && !state.terminationReason().isBlank()
                && state.stepCount() > 0;
    }

    /** 闂備礁鎲＄敮鍥磹閺嶎厼钃熼柛銉簵娴滃綊鏌熼幆褍鏆辨い銈呮嚇閺岋絽顭ㄦ惔锛勪哗濡炪倧绲婚崝鎴濐嚕娴兼惌鏁嶆慨姗嗗幖閸撱劑姊哄ú璁崇敖闁哥姵鍔欓、妤€顭ㄩ崼婵娦曞銈嗙墬缁秹寮宠箛鎾佺懓顭ㄩ崘鈺婃！濡炪們鍎遍幊妯侯嚕婵犳艾唯鐟滃秵绂掗鐐寸厸闁割偅绻冮幆鍫ユ煕閳哄偆娈滃┑?*/
    private boolean checkpointEnabled() {
        return config.getCheckpoint().isEnabled() && checkpointStore != null;
    }

    /** 闂備焦鐪归崹濠氬窗閹版澘鍨傛慨妯块哺鐎氭岸姊洪崹顕呭剳婵犫偓閹绢喗鐓欑紒瀣瀹告繃鎱ㄧ憴鍕垫疁闁轰礁绉撮悾婵嬪焵椤掑嫬鏋侀柕鍫濇椤╂煡骞栧ǎ顒€鈧牜娑甸崼鏇熲拺闁圭粯甯炲暩闂?闂備浇顕栭崢褰掑垂瑜版崵鍥嚑椤掍礁鐝伴梺绯曞墲椤ㄥ懘鎮炴總鍛婄厱婵﹩鍓欓〃娆戠磼閺傝法鎽犵紒鍌涘笧閹风娀骞撻幒婵囧尃闂?*/
    private String fingerprintOf(AgentRequest request) {
        return AgentCheckpointFingerprinter.fingerprint(request);
    }

    /** 濠电姰鍨煎▔娑氣偓姘煎櫍楠炲啯绻濋崶褑袝闁诲繒鍋熼搹搴ㄥ礉閸曨垱鐓欑紒瀣仢閳ь兛绮欓獮鎴︽晲婢跺娅栧┑顔斤供閸嬪棛绮绘禒瀣€垫繛鎴烆仾椤忓懎顕遍柟閭﹀幗婵瓨绻濇繝鍌氭殭闂傚懏锕㈤弻鏇㈠幢閺囩媭妲梺?suspendStore闂備焦瀵х粙鎴︽嚐椤栫偞鍤愰柣鏂挎啞娴溿倝鏌￠崒娑橆嚋缂佲偓閳ь剟姊洪崨濠勬噽闁搞劏椴哥粋鎺楊敊閽樺绐為悗骞垮劚濞诧箓鎮炬潏鈺冪＝濞达絽鎽滄晶娑㈡煃瑜滈崗娑氱矆娓氣偓楠炴牠鏌ㄧ€ｎ偄鍔呴梺鍝勫€婚崕鐢稿磻?*/
    private AgentResponse handleSuspendSync(ReactAgentState state,
                                            @Nullable TraceContext traceContext,
                                            AgentLoopContext loopContext) {
        executionPersistence.saveWorkspaceForSuspend(state);
        String suspendMessage = resolveSuspendMessage(state);
        var suspendedState = state.toBuilder()
                .finalOutput(suspendMessage)
                .terminationReason(resolveSuspendTerminationReason(state))
                .completionReason(CompletionReason.SUSPENDED)
                .completionMode(CompletionMode.SUSPENDED)
                .build();
        if (suspendStore != null) {
            suspendStore.save(SuspendedAgent.from(suspendedState, objectMapper));
            agentLoop.scheduleWakeupIfNeeded(suspendedState);
            log.info("Agent 閻庡湱顭堝鍓佲偓鍨礈閹秆冪暋閹峰苯鐝梺褰掓櫜濡炴帞绮诲▎鎾崇? traceId={}, reasonType={}",
                    suspendedState.traceId(), suspendedState.suspendReason().getClass().getSimpleName());
        } else {
            log.warn("Agent 闁荤姴娲弨閬嶆儑娴兼潙绠伴柛灞剧箖瀹曞啿霉?SuspendStore 闂佸搫鐗滄禍婵嬪极閻愬搫绀傞柕澶樺灣缁€澶愭煛閸愵亜校缁绢厼鐖奸獮鎰媴妞嬪海鏆梺? traceId={}", suspendedState.traceId());
        }
        String assistantEntryId = executionPersistence.persistAssistantSync(
                suspendedState,
                serializeReactStepsJson(suspendedState.steps()),
                loopContext);
        executionPersistence.markTurnCompleted(suspendedState, assistantEntryId, ChatTurnStatus.SUSPENDED);
        return buildAgentResponse(suspendedState, assistantEntryId, null, aggregateTokenUsage(traceContext));
    }

    /**
     * 处理流式挂起。
     *
     * <p>这里优先把“现在需要用户补充信息”的信号尽快发给前端，
     * 避免等 transcript/turn 落库全部完成后，输入框才恢复可用。
     * 对用户来说，挂起是一种交互切换，时机应优先于后台持久化。</p>
     */
    private void handleSuspendStreaming(ReactAgentState state, String streamId,
                                        SseSessionManager sseManager,
                                        AgentLoopContext loopContext) {
        executionPersistence.saveWorkspaceForSuspend(state);
        String suspendMessage = resolveSuspendMessage(state);
        var suspendedState = state.toBuilder()
                .finalOutput(suspendMessage)
                .terminationReason(resolveSuspendTerminationReason(state))
                .completionReason(CompletionReason.SUSPENDED)
                .completionMode(CompletionMode.SUSPENDED)
                .build();
        if (suspendStore != null) {
            var suspendedAgent = SuspendedAgent.from(suspendedState, objectMapper).toBuilder()
                    .streamId(streamId).build();
            suspendStore.save(suspendedAgent);
            agentLoop.scheduleWakeupIfNeeded(suspendedState);
            log.info("濠电偟绻濈粈浣烘?Agent 閻庡湱顭堝鍓佲偓鍨礈閹秆冪暋閹峰苯鐝梺褰掓櫜濡炴帞绮诲▎鎾崇? traceId={}, reasonType={}, streamId={}",
                    suspendedState.traceId(), suspendedState.suspendReason().getClass().getSimpleName(), streamId);
        } else {
            log.warn("Agent 闁荤姴娲弨閬嶆儑娴兼潙绠伴柛灞剧箖瀹曞啿霉?SuspendStore 闂佸搫鐗滄禍婵嬪极閻愬搫绀傞柕澶樺灣缁€澶愭煛閸愵亜校缁绢厼鐖奸獮鎰媴妞嬪海鏆梺? traceId={}", suspendedState.traceId());
        }

        // 先向前端发出挂起信号，让输入框立即恢复为“继续回复即可”的自然对话状态。
        sseManager.sendEvent(streamId, SseEventType.AGENT_SUSPENDED, buildSuspendedEventPayload(suspendedState, suspendMessage));
        sseManager.closeEmitter(streamId);

        // 挂起后的 transcript/turn 落库放在事件发送之后，避免用户先感知到“卡住”。
        try {
            String assistantEntryId = executionPersistence.persistAssistantStreaming(
                    suspendedState,
                    suspendMessage,
                    suspendedState.reasoningSummary(),
                    null,
                    serializeReactStepsJson(suspendedState.steps()),
                    loopContext);
            executionPersistence.markTurnCompleted(suspendedState, assistantEntryId, ChatTurnStatus.SUSPENDED);
        } catch (Exception e) {
            log.error("流式挂起后续持久化失败: sessionId={}, traceId={}, error={}",
                    suspendedState.sessionId(), suspendedState.traceId(), e.getMessage(), e);
        }
    }

    /** 构建 AGENT_SUSPENDED 事件载荷。 */
    private Map<String, Object> buildSuspendedEventPayload(ReactAgentState suspendedState, String suspendMessage) {
        var suspendedEvent = new HashMap<String, Object>();
        suspendedEvent.put("traceId", suspendedState.traceId());
        suspendedEvent.put("sessionId", suspendedState.sessionId());
        suspendedEvent.put("completionMode", CompletionMode.SUSPENDED);
        suspendedEvent.put("turnStatus", ChatTurnStatus.SUSPENDED);
        suspendedEvent.put("contentRole", OutputContentRole.SUSPEND_PROMPT.name());
        suspendedEvent.put("reasonType", suspendedState.suspendReason().getClass().getSimpleName());
        String reasonSourceId = resolveSuspendReasonSourceId(suspendedState.suspendReason());
        if (reasonSourceId != null && !reasonSourceId.isBlank()) {
            suspendedEvent.put("reasonSourceId", reasonSourceId);
        }
        suspendedEvent.put("reasonDetail", agentLoop.formatSuspendReason(suspendedState.suspendReason()));
        suspendedEvent.put("terminationReason", resolveSuspendTerminationReason(suspendedState));
        suspendedEvent.put("content", suspendMessage);
        suspendedEvent.put("suspendedAt", Instant.now().toString());
        if (suspendedState.turnId() != null && !suspendedState.turnId().isBlank()) {
            suspendedEvent.put("turnId", suspendedState.turnId());
        }
        return suspendedEvent;
    }

    /** 提取挂起原因的稳定标识，供前端区分“等待用户补充”这类接续模式。 */
    @Nullable
    private String resolveSuspendReasonSourceId(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait workflowWait -> workflowWait.executionId();
            case SuspendReason.UserConfirmation confirmation -> confirmation.confirmationId();
            case SuspendReason.RemoteDelegation remoteDelegation -> remoteDelegation.remoteTaskId();
            case SuspendReason.ScheduledWakeup _ -> null;
            case SuspendReason.ExternalDataWait externalDataWait -> externalDataWait.dataSourceId();
        };
    }

    /** 婵炴潙鍚嬮敋闁告ɑ绋掔粚鍗炩攽閸喐鐣┑鈽嗗灙閳ь剙纾埀顒€鍢查蹇涙嚑椤掑倻姊鹃梺姹囧灮閸犳劙宕瑰鑸靛剭闁告洦鍠楃€氬綊姊婚崒銈呮灍闁哄鍟村鐢割敆婵犲嫮顦梻渚囧墮閻忔繈宕㈤妶澶婄闁稿本绻冨畷鎶芥煛閸愨晛鍔舵い顐閸栨牠鎳￠妶鍥х厷闂佸搫鍊稿ú锕傘€佸鍥ㄥ暫闁糕剝鐟︾壕浼存煏?*/
    private String resolveSuspendMessage(ReactAgentState state) {
        if (state.finalOutput() != null && !state.finalOutput().isBlank()) {
            return state.finalOutput().strip();
        }
        String reasonDetail = agentLoop.formatSuspendReason(state.suspendReason());
        if (reasonDetail == null || reasonDetail.isBlank()) {
            return "\u6211\u8fd8\u7f3a\u5c11\u7ee7\u7eed\u5904\u7406\u6240\u9700\u7684\u4fe1\u606f\u3002\u4f60\u56de\u590d\u540e\uff0c\u6211\u4f1a\u63a5\u7740\u5f53\u524d\u8fdb\u5ea6\u7ee7\u7eed\u5904\u7406\u3002";
        }
        return "\u5f53\u524d\u4efb\u52a1\u6682\u65f6\u65e0\u6cd5\u7ee7\u7eed\uff1a" + reasonDetail + "\u3002\u6761\u4ef6\u6ee1\u8db3\u540e\u6211\u4f1a\u4ece\u5f53\u524d\u8fdb\u5ea6\u7ee7\u7eed\u5904\u7406\u3002";
    }

    /** 闂佸湱顭堥崐浠嬪箲閿濆洨纾奸柛顐ゅ暱閸嬫挻鎷呮搴℃灎闂佺绻愰悧濠囨焾閵娾晜鍋ㄩ柕濞垮劜閸庢洟鏌ｅ搴＄仯缂侇喕鍗冲畷娆撴惞鐟欏嫮鏆?terminationReason闂佹寧绋戞總鏃傜矚閼哥數顩查幖绮光偓鎰佹瀫缂備焦妫忛崹鐢告儊閹达箑绀嗘い鎰剁导閸掓帒顭跨捄铏剐ユい鏇樺灪缁嬪﹥寰勯崼姘壕?*/
    private String resolveSuspendTerminationReason(ReactAgentState state) {
        if (state.terminationReason() != null && !state.terminationReason().isBlank()) {
            return state.terminationReason();
        }
        return "suspended";
    }

    /** 闂備礁鎼鍛偓姘嵆閸┾偓妞ゆ帒鍊稿暩闂佽桨闄嶉崐婵嬬嵁鐎ｎ亞鏆嬮柡澶庡劵椤斿鏌ｉ悩鍙夌┛鐎规洟娼ч埢鎾诲箣閿曗偓缁犳娊鏌曟繛褍瀚埀顒傚仱閺岀喖鎳為妷顔惧姼濡炪倐鏅粻鎾诲极瀹ュ洣娌柤娴嬫櫈缁绘垿姊洪崫鍕闁稿鎸剧槐鎾存媴閸濄儱顤€缂備礁顦遍崗姗€鐛笟鈧、娑橆潩椤撶儐浼呴梻浣告啞閿氭俊顐ｎ殙閵囨劙宕滄担鐟邦€涢梺鍝勵槼濞夋洜绮旈崸妤佸€堕柣鎰摠缁€鍫ユ煏閸ャ劌鍝哄┑?*/
    private String buildReasoningSummary(ReactAgentState state,
                                         @Nullable TraceContext traceContext) {
        int steps = state.stepCount();
        int tokens = state.budget() != null ? state.budget().tokensUsed() : 0;
        String modelId = DEFAULT_MODEL_ID;
        if (traceContext != null) {
            var traceSteps = traceContext.steps();
            for (int i = traceSteps.size() - 1; i >= 0; i--) {
                if (traceSteps.get(i) instanceof LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    tokens = traceContext.totalInputTokens() + traceContext.totalOutputTokens();
                    break;
                }
            }
        }
        return "\u672c\u8f6e\u63a8\u7406\u5df2\u5b8c\u6210\uff0c\u4f7f\u7528\u6a21\u578b %s\uff0c\u7ecf\u8fc7 %d \u4e2a\u63a8\u7406\u6b65\u9aa4\uff0c\u7d2f\u8ba1\u7ea6 %d \u4e2a Token\u3002"
                .formatted(modelId, steps, tokens);
    }
    /** 闂佺懓鍚嬪娆戞崲閹版澘鍨傛い蹇撶墕缁€?ReAct 婵犳鍠楃缓鍧楀磿閻㈠壊鏁侀柛鎰靛枛缁€鍡樹繆閵堝懎顏ラ柍褜鍓欓崯鎾极瀹ュ洣娌柤娴嬫杹閺佲偓濠电偛鐡ㄧ划宀€鈧稈鏅濋埀顒€鐏氬鍦矙婵犲洤宸濇い鎾跺剱閸ゆ瑩姊?transcript闂?*/
    @Nullable
    private String serializeReactStepsJson(@Nullable List<ReactStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return ReactStepSerializer.serializeToJson(steps, objectMapper);
    }
    /** 婵犳鍠氶幊鎾趁洪敃鍌氱劦?trace 濠电偞鍨堕幖鈺呭矗閳ь剚銇勯弬鎸庣┛闁靛洦鍔欏鎾偐瀹曞洦娈?token 濠电偠鎻紞鈧繛澶嬫礋瀵偊濡舵径瀣帓闂婎偄娲㈤崕宕囩矆婢跺娈介柣鎰摠閺嬪嫮绱掔紒妯哄摵闁诡喗鍎抽埢搴ㄥ箛椤撴繄甯涢梻浣告啞閼瑰墽鑺遍懖鈺冨崥闁哄鍨熼弸?LLM 闂佽崵濮撮鍛村疮娴兼潙鏋侀柕鍫濐槹閸庡秹鏌涢弴銊ュ姅濞撴埃鍋撶€规洖婀遍埀顒婄秵閸嬪棝宕规總鍛婂仯闁搞儜鍕垫闂?*/
    private TokenUsage aggregateTokenUsage(@Nullable TraceContext traceContext) {
        String modelId = DEFAULT_MODEL_ID;
        int promptTokens = 0;
        int completionTokens = 0;
        if (traceContext != null) {
            promptTokens = traceContext.totalInputTokens();
            completionTokens = traceContext.totalOutputTokens();
            var steps = traceContext.steps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (steps.get(i) instanceof LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    break;
                }
            }
        }
        return new TokenUsage(promptTokens, completionTokens,
                promptTokens + completionTokens, modelId);
    }

    /** 缂傚倸鍊烽懗鍫曞窗瀹ュ洨鍗氶柟缁㈠枛閸戠娀鏌涢弴銊ョ仚闁?AgentResponse闂備焦瀵х粙鎴炵附閺冨倹宕叉慨妯块哺鐎氭艾霉閿濆懏鍟為柛濠呴哺閹便劌鈹戦崟顐㈠闂侀€炲苯鍘哥紒鑸佃壘椤﹥顦版惔锝嗭紡閻熸粌顑夐崺鈧い鎴ｆ硶閸斿秹鎮楅崹顐ｇ闁圭鍥ㄦ啣闁稿本绮庨幉锕傛⒑閸濆嫷妫ラ柕鍫濇川閸炴挳鎮楅崹顐ｇ凡閻庢凹鍓熼幃楦款槻闁崇粯鎹囧Λ鍐ㄢ槈濡偐鈻旈柣搴＄仛濠㈡鈧凹浜炵划鈺呭箻椤旇棄娈滃┑鐑囩秵閸忔﹢宕?*/
    private AgentResponse buildAgentResponse(ReactAgentState state,
                                             @Nullable String assistantEntryId,
                                             @Nullable List<com.lifepilot.interaction.web.model.A2uiComponent> a2uiComponents,
                                             @Nullable TokenUsage tokenUsage) {
        return new AgentResponse(
                state.traceId(),
                state.sessionId(),
                state.turnId(),
                state.taskMode(),
                state.finalOutput() != null ? state.finalOutput() : "",
                state.budget().tokensUsed(),
                state.stepCount(),
                state.terminationReason(),
                state.completionReason(),
                assistantEntryId,
                a2uiComponents,
                tokenUsage,
                state.completionMode(),
                state.resumedFromTraceId(),
                resolveTurnStatus(state)
        );
    }

    /** 闂佽绻愮换鎰涘Δ鍛埞闁圭虎鍠楅悞?completion/suspend 缂傚倸鍊烽悞锕傚箰婵犳碍鍊跺鑸靛姇閸欏﹪鏌ｅΟ鍨毢婵炲牏顭堥埥澶愬箻閾忣偅鍎撴繝鈷€鍛ョ紒顔规櫊椤㈡稑顫濋鍐惧敼婵犵數鍋為崹鐢告偋閹版澘鍨傞柛宀€鍋為崕?turn 闂備胶绮…鍫ュ春閺嶎厼鐒垫い鎴濇健濡剧兘鏌?*/
    private ChatTurnStatus resolveTurnStatus(ReactAgentState state) {
        if (state.suspended()) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (state.completionMode() == CompletionMode.DEGRADED
                || (state.terminationReason() != null && !state.terminationReason().isBlank())) {
            return ChatTurnStatus.DEGRADED;
        }
        return ChatTurnStatus.SUCCESS;
    }

    /** 闂備礁鎼粔鏉懨洪埡鍜佹晩闁搞儺鍓欓悘铏節婵犲倸顏柟鐤哺缁绘盯宕辫箛鎾斥拤缂備椒绶ょ粻鎴︻敋閿濆牏鐤€闁规儳澧庨敍婵嬫煟閵忊晛鐏﹂柡鍛板皺閸掓帒鈽夐姀鐘碉紮闂佺粯鎸稿ù椋庢崲娓氣偓閺岋綁濮€閻樿尙绉紓浣诡殔閸婄粯绂掗敃鍌氱＜婵犻潧妫欓鍥⒑閸涘﹥鐓熼柛鏃€鍨垮畷鎶藉传閵夛箑鐝伴梺鍝勬处閵囨盯宕戦幘鏂ユ斀闁割偆鍠撻弳鐘绘⒑閸涘﹦澧柟铏崌钘熷┑鐘插亞閸ゆ洟鏌涚仦鐐殤闁糕晜顨婇弻?*/
    public static final class RetryableStreamingException extends RuntimeException {

        public RetryableStreamingException(Throwable cause) {
            super(cause);
        }
    }
}
