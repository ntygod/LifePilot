# Proactive AI Intelligence Research Report

> Research conducted April 2026 for ZhiWei (知微) proactive engine design.

---

## 1. Academic Research & Concepts

### 1.1 Proactive Conversational AI -- The Definitive Survey

**Source**: Deng et al., "Proactive Conversational AI: A Comprehensive Survey" (ACM TOIS, 2025) -- extended from IJCAI 2023 Survey Track.

**Definition**: A proactive dialogue system is one that *leads* the conversation direction toward achieving pre-defined targets or fulfilling goals *from the system side*, rather than passively responding.

**Three Key Elements of Proactivity**:
1. The system has its own *goal* or *target* (not just responding to user goals)
2. The system *initiates* actions or topic shifts (not just reacting)
3. The system exercises *strategic planning* to achieve its goals over multiple turns

**Taxonomy of Proactive Problems by Dialogue Type**:

| Dialogue Type | Problem | Description |
|---|---|---|
| **Open-domain** | Topic-leading | Smoothly steering conversation toward a target topic over multiple turns |
| **Open-domain** | Non-collaborative dialogue | Negotiation, persuasion -- system and user have different goals |
| **Open-domain** | Emotional support | Proactively identifying distress and offering support |
| **Task-oriented** | Clarification | Proactively asking when user intent is ambiguous |
| **Task-oriented** | Recommendation | Enriching responses with unsolicited but useful information |
| **Task-oriented** | System-initiated sub-dialogues | Initiating sub-conversations to overcome obstacles |
| **Info-seeking** | Query suggestion | Suggesting follow-up questions the user hasn't asked yet |
| **Info-seeking** | Over-specified query management | Handling queries that are too narrow or contradictory |

**Three Levels of Proactivity** (for task-oriented systems):

| Level | Scope | Example |
|---|---|---|
| **Turn-level** | Enriching a single response | Adding "by the way, there's a discount today" to an answer |
| **Sub-dialogue-level** | Initiating a multi-turn sub-conversation | "Before I book that, let me clarify -- did you mean the downtown or airport location?" |
| **Dialogue-level** | Driving the entire conversation | Persuasion, negotiation, proactive goal pursuit across entire session |

**Actionable Insight for ZhiWei**: Map the existing reactive agent loop to these three levels. Turn-level proactivity can be added to existing response generation. Sub-dialogue proactivity requires the agent loop to support "system-initiated" conversation branches. Dialogue-level proactivity requires persistent goals that survive across sessions.

---

### 1.2 Inner Thoughts Framework (CHI 2025)

**Source**: "Proactive Conversational Agents with Inner Thoughts" (ACM CHI 2025, arXiv:2501.00383)

**Core Idea**: Rather than reacting to turn-taking cues, a proactive agent maintains a *continuous, covert train of thoughts in parallel* to the visible conversation. It formulates internal thoughts and strategically seeks the right moment to contribute.

**Architecture**:
- Parallel processing: Internal reasoning runs alongside visible dialogue
- Motivation modeling: The system assesses its intrinsic *desire* to express formulated thoughts
- Timing mechanisms: Strategic selection of appropriate moments for contribution

**Results**: Agents with Inner Thoughts significantly outperformed next-speaker prediction baselines on turn appropriateness, coherence, anthropomorphism, and perceived engagement.

**Actionable Insight for ZhiWei**: Implement a background "thought generation" process that runs during user idle time. The agent generates candidate proactive messages but gates them through a timing/relevance filter before surfacing. This is analogous to having a `ProactiveThoughtQueue` that the agent's main loop periodically evaluates.

---

### 1.3 ContextAgent (NeurIPS 2025)

**Source**: "ContextAgent: Context-Aware Proactive LLM Agents with Open-World Sensory Perceptions" (arXiv:2505.14668)

**Architecture** (two-phase pipeline):

**Phase 1 -- Proactive-Oriented Context Extraction**:
- Visual context via VLMs (custom prompts, not generic captioning)
- Acoustic context via speech recognition
- Notification context from calendars, reservations, alerts
- Persona context from user identity, preferences, behavioral history

**Phase 2 -- Context-Aware Proactive Reasoning**:
- Input: sensory + persona contexts
- Output: thought traces, **proactive score (1-5)**, tool chains
- Action triggered when proactive score >= threshold (3)
- Fine-tuned with CoT reasoning traces distilled from Claude-3.7-Sonnet

**Benchmark (ContextAgentBench)**: 1,000 samples across 9 daily scenarios, 20 tools. Achieved 89.4% accuracy for proactive predictions.

**Actionable Insight for ZhiWei**: The **proactive score** concept is directly applicable. Each candidate proactive action should be scored on a 1-5 scale considering urgency, relevance, and user preference, with a configurable threshold. The two-phase separation (context extraction vs. reasoning) is a clean architecture that maps well to ZhiWei's existing pipeline.

---

### 1.4 Preference-Aligned Proactive Assistants (arXiv, Feb 2026)

**Source**: "After Talking with 1,000 Personas: Learning Preference-Aligned Proactive Assistants From Large-Scale Persona Interactions" (arXiv:2602.04000)

**Five-Dimensional Preference Model**:

| Dimension | What It Captures |
|---|---|
| Scheduling Preference | When interactions are welcome (time-of-day, activity type) |
| Domain Prioritization | What topics/domains the user values |
| Autonomy Level | How much control user retains over actions |
| Communication Style | Tone, detail level, language preferences |
| Context Adaptation | How behavior should vary across environments |

**Two-Stage Architecture**:
1. **Population-level**: Train a 3B model on synthetic persona interactions (LoRA, supervised fine-tuning)
2. **Individual adaptation**: Activation vector steering at inference time -- no parameter updates needed

**Key Results**:
- +483.9% improvement in Temporal Appropriateness Index vs baseline
- Activation steering achieves 99% of RLHF performance while running entirely on-device
- Behavioral feedback (accept/reject) outperforms stated preferences (61.3% vs 57.7% accuracy)

**Actionable Insight for ZhiWei**: The five-dimensional preference model is an excellent template for ZhiWei's user profile. Store these five dimensions per user. Update them based on *behavioral signals* (did the user act on the proactive suggestion? dismiss it? modify it?) rather than explicit configuration. The activation steering approach shows you can personalize without retraining.

---

### 1.5 PASK: Intent-Aware Proactive Agents (arXiv, Apr 2026)

**Source**: "PASK: Toward Intent-Aware Proactive Agents with Long-Term Memory" (arXiv:2604.08000)

**DD-MM-PAS Paradigm**:
- **Demand Detection (DD)**: Infer latent user needs from continuous context
- **Memory Modeling (MM)**: Hierarchical memory for evolving user understanding
- **Proactive Agent System (PAS)**: Operational backbone

**IntentFlow Decision Architecture** (three control tokens):
- `<silent>` -- no intervention needed
- `<fast_intervention>` -- immediate response from current context
- `<full_assistance>` -- memory-grounded deep reasoning required

**Hierarchical Memory**:

| Layer | Analogous To | Access Pattern |
|---|---|---|
| User Memory | L1 Cache | Stable traits; zero-latency via KV-cache |
| Workspace Memory | L2 Working Memory | Session dynamics; continuous updates |
| Global Memory | Long-term Storage | Tree-structured semantic tags; async RAG |

**Key Results**: 84.2% balanced accuracy; maintained 81.8% accuracy even at turns 57-60 (only 5% decline), while competitors degraded 17-74%.

**Actionable Insight for ZhiWei**: The three-token decision model (`silent`/`fast`/`full`) maps perfectly to ZhiWei's proactive engine. Most heartbeat cycles should produce `silent`. The memory hierarchy mirrors ZhiWei's existing 4-layer memory (Working L1 / Episodic L2 / Semantic L3 / Procedural L4). The key innovation is separating *demand detection* (cheap, fast) from *full assistance* (expensive, thorough).

---

### 1.6 Long-Term Task-Oriented Proactive Agent (arXiv, Jan 2026)

**Source**: "Long-term Task-oriented Agent: Proactive Long-term Intent Maintenance in Dynamic Environments" (arXiv:2601.09382)

**Two Key Capabilities**:
1. **Intent-Conditioned Monitoring**: Agent autonomously formulates trigger conditions based on dialog history
2. **Event-Triggered Follow-up**: Agent actively engages user upon detecting useful environmental updates

**Example**: User asks "find me a flight to Tokyo under $500." Instead of just searching now, the agent *continues monitoring flight prices* and proactively notifies the user when a match appears days later.

**Actionable Insight for ZhiWei**: This is the "persistent intent" pattern. ZhiWei should maintain a list of active user intents/goals with associated trigger conditions. Each heartbeat cycle should check these conditions against fresh data. This transforms the agent from a "fire and forget" responder to a "persistent watcher."

---

### 1.7 Developer Interaction with Proactive AI (Field Study, Jan 2026)

**Source**: "Developer Interaction Patterns with Proactive AI: A Five-Day Field Study" (arXiv:2601.10253)

**Concrete Findings** (229 interventions across 15 developers):

| Trigger Type | Engagement Rate | Dismissal Rate |
|---|---|---|
| Post-commit review | 52% | -- |
| Ambiguous prompt | 46% | -- |
| Declined edit follow-up | 31% | 62% |

**Interpretation Time**: Proactive suggestions took 45.4s to interpret vs 101.4s for reactive (p=0.0016) -- because proactive suggestions arrived pre-formatted as patches.

**Critical Design Principles**:
- **DP1 Timing**: Anchor suggestions to task boundaries (commits, saves, context switches), NOT mid-task
- **DP2 Context**: Use workspace signals (open files, recent edits, repo semantics)
- **DP3 Transparency**: Show inline diffs, confidence indicators, explain *why* this moment
- **DP4 Control**: Configurable frequency, timing, suggestion types

**Key Quote**: "Models tend to provide as much assistance as possible, instead of providing necessary assistance when the user needs it." (50% false-alarm rate even in best models)

**Actionable Insight for ZhiWei**: The 52% acceptance at task boundaries vs 31% mid-task is a powerful signal. ZhiWei's proactive engine should detect "natural pause points" (session end, topic change, returning after absence) rather than interrupting active work. The false-alarm rate problem is real -- err on the side of fewer, higher-quality proactive messages.

---

### 1.8 Proactive Dialogue Levels of Autonomy (CHI 2024)

**Source**: CHI 2024 Workshop on Building Trust in CUIs

**Four Levels of Proactive Autonomy**:

| Level | Name | Agent Behavior | User Control |
|---|---|---|---|
| 0 | None | No proactive action | Full |
| 1 | Notification | Inform user that alternatives exist | High |
| 2 | Suggestion | Present a recommendation; user confirms/declines | Medium |
| 3 | Intervention | Execute action on user's behalf | Low |

**Trust Calibration Findings**:
- Users prefer low-to-medium proactivity (Levels 1-2) over high (Level 3) or none (Level 0)
- Fully autonomous behavior *fails* to establish adequate trust
- Task difficulty and domain expertise influence acceptable level
- Proactive level should adapt to context dynamically

**Actionable Insight for ZhiWei**: Implement an "Autonomy Dial" that lets users configure their preferred level per domain. Default to Level 1 (notification) for new features, graduate to Level 2 (suggestion) after trust is established, and only reach Level 3 (intervention) for explicitly delegated tasks.

---

### 1.9 ProAgentBench (arXiv, Feb 2026)

**Source**: "ProAgentBench: Evaluating LLM Agents for Proactive Assistance with Real-World Data" (arXiv:2602.04482)

**Key Finding**: Models trained on *real-world* behavioral data outperform those trained on synthetic data (74.0% vs 62.1% accuracy for LLaMA-3.1-8B). The benchmark decomposes proactive assistance into "When to Assist" (timing) and "How to Assist" (content generation).

**Actionable Insight for ZhiWei**: Log all proactive attempts and user responses. Over time, this real-world data becomes the most valuable training signal for improving the proactive engine.

---

## 2. Open Source Projects & Products

### 2.1 OpenClaw (68,000+ GitHub stars, launched Jan 2026)

**Architecture**: Three-layer model (Channel / Brain / Body):
- **Channel Layer**: Messaging adapters (Signal, Telegram, Discord, WhatsApp, iMessage)
- **Brain Layer**: ReAct loop with memory, skill loading, context assembly
- **Body Layer**: Tools, MCP integration, browser automation

**Proactive Mechanisms**:
1. **Heartbeat System**: Configurable interval (default 30 min). Gateway injects `HEARTBEAT.md` content + wakeup message. Agent evaluates and either acts or returns `HEARTBEAT_OK` (silently discarded).
2. **Cron Scheduling**: Full cron syntax via `croner` library. Per-job model/thinking overrides.
3. **Event-Driven Webhooks**: External services push events to Gateway.

**Memory**: File-based Markdown (MEMORY.md, SESSION-STATE.md, daily episodic logs). Editable by user.

**Super Proactive Agent Skill Architecture**:
- Write-Ahead Logging (WAL) protocol for state recovery
- Three-tier memory: Episodic (daily logs) / Semantic (MEMORY.md) / Procedural (skills/)
- Task queue (QUEUE.md) with Ready/In Progress/Done/Blocked kanban columns
- Autonomous schedule: 30 min (task check), 4 hours (deep work), daily (summary)

**Relevance to ZhiWei**: OpenClaw's architecture is remarkably similar to ZhiWei's. The key differences are: ZhiWei uses SQLite/structured storage vs OpenClaw's Markdown files, and ZhiWei has a more sophisticated 4-layer memory. The Heartbeat pattern and QUEUE.md concept are directly applicable.

URL: https://github.com/openclaw/openclaw

---

### 2.2 Mem0 (48,000+ GitHub stars)

**What**: Framework-agnostic memory layer for AI agents. You `add()` conversations and Mem0 extracts facts automatically.

**Architecture**: Extraction pipeline decomposes conversations into atomic facts, stored in vector + graph storage. Developer controls inputs; system handles decomposition.

**Proactive Relevance**: Mem0 enables *memory-driven proactivity* -- the agent can surface relevant past context without the user asking. The `search()` API retrieves memories semantically similar to current context.

**Key Technical Detail**: Mem0 v1.0 supports graph memory (knowledge graph alongside vectors), enabling relationship-aware retrieval.

URL: https://github.com/mem0ai/mem0

---

### 2.3 Letta (MemGPT) -- Stateful Agent Platform

**What**: Full agent runtime where agents *self-edit* their memory. The agent decides what to remember by calling memory functions during its reasoning loop.

**Architecture**: Treats LLM context like virtual memory (MemGPT paper concept):
- Core memory: Always in context window
- Recall memory: Searchable conversation history
- Archival memory: Long-term knowledge base

**Proactive Relevance**: Letta's self-editing memory enables the agent to build increasingly rich user models over time -- a prerequisite for quality proactive behavior.

URL: https://github.com/letta-ai/letta

---

### 2.4 Zep / Graphiti -- Temporal Knowledge Graph for Agent Memory

**What**: Knowledge graph engine that maintains temporal validity of facts.

**Architecture** (three-tier hierarchy):
1. **Episode Subgraph**: Raw data (messages, events)
2. **Semantic Entity Subgraph**: Extracted entities and relationships
3. **Community Subgraph**: Clustered entity groups with summaries

**Bi-Temporal Model**: Each fact has two timelines:
- Timeline T (event time): When the fact became true in the real world
- Timeline T' (transaction time): When Zep learned about it

Facts store four timestamps: `t'_created`, `t'_expired`, `t_valid`, `t_invalid`. Contradictions invalidate old edges rather than deleting them.

**Performance**: 94.8% accuracy on DMR benchmark; 90% latency reduction vs full-context; 1.6k tokens vs 115k baseline.

**Proactive Relevance**: Temporal awareness is critical for proactive systems. Zep can answer "what changed since the agent last checked?" -- the fundamental question of every heartbeat cycle.

URL: https://github.com/getzep/graphiti

---

### 2.5 Screenpipe -- Ambient Awareness SDK

**What**: Open-source screen/audio recording with local-first AI search (16,700+ stars).

**Architecture**:
- Event-driven capture (app switches, clicks, typing pauses -- not constant recording)
- OS accessibility tree for text extraction (faster than OCR), OCR fallback
- Local SQLite + FTS5 for search
- MCP server integration for AI assistant queries
- ~300 MB/8hr vs ~2 GB with continuous recording

**Proactive Relevance**: Demonstrates how to build "ambient awareness" without overwhelming storage or privacy concerns. Event-driven capture is far more efficient than continuous recording.

URL: https://github.com/mediar-ai/screenpipe

---

### 2.6 ProactiveAgent (GitHub Library)

**What**: Python library that transforms reactive AI agents into proactive ones with intelligent timing.

**Three-Phase Decision Cycle**:
1. **Decision Engine**: Should I respond? (context analysis, temporal evaluation, engagement monitoring)
2. **Message Generation**: What to say?
3. **Sleep Calculator**: How long to wait?

**Sleep Calculator Types**:
- AI-Based: Natural language config ("Use the pace of a normal text chat")
- Pattern-Based: Keyword matching
- Function-Based: Custom adaptive logic
- Static: Fixed intervals

**Configuration**: `min_response_interval` (30s), `max_response_interval` (600s), `probability_weight` (0-1)

URL: https://github.com/leomariga/ProactiveAgent

---

### 2.7 Leon -- Open Source Personal Assistant

**What**: Self-hosted personal AI assistant with memory, tools, and agentic execution.

**Proactive Feature**: "Compact self-model and bounded proactive pulse system" -- periodic self-assessment without flooding context.

URL: https://github.com/leon-ai/leon

---

## 3. Commercial Products with Proactive Features

### 3.1 Google Assistant / Gemini

**Proactive Architecture**:
- **Visual Snapshot**: Curated info based on time of day, location, recent interactions
- **App Actions + Shortcuts**: After user completes a task, dynamic shortcuts are pushed to Google, enabling contextually relevant future suggestions
- **Ambient Mode**: Proactive experience on charging devices (weather, agenda, reminders)
- **Gemini Overlay**: Moving from full-screen to floating overlay panel; code references show "contextual suggestions" appearing proactively based on app content

**Key Pattern**: Context = time + location + recent interactions + usage patterns. Suggestions emerge from intersection of these signals.

### 3.2 Apple Intelligence / Siri

**Architecture Challenges**: The initial architecture for new Siri "failed to meet Apple's stringent reliability standards" -- pointing to deep challenges in merging multiple systems into a cohesive proactive assistant. Delayed to iOS 26.4 (Feb 2026).

**Approach**: Privacy-first federated learning for personalization. On-device processing + cloud for complex tasks. Proactive suggestions, cross-app actions, on-screen understanding.

**Key Lesson**: Even with billions in investment, reliable proactive behavior is *hard*. Apple's struggles validate a cautious, incremental approach.

### 3.3 Microsoft Copilot

**Architecture**:
- **Microsoft Graph**: Unified data fabric connecting OneDrive, SharePoint, Exchange, Teams
- **Semantic Index**: Built from Graph data, enabling contextual grounding
- **Orchestration Layer**: Prompt interpretation, grounding, response management
- **Proactive Prompts**: Contextual prompt starters showing case/conversation/email awareness
- **Power Automate Flows**: Proactive messaging triggered by workflows

**Key Pattern**: The power comes from the *data graph* -- connecting all user data into a queryable semantic layer. Proactive suggestions are only as good as the context they're grounded in.

### 3.4 Poke -- Text-Based Proactive Assistant

**What**: AI assistant operating entirely via text messaging (iMessage, SMS, Telegram). $25M funded, $100M valuation.

**Proactive Features**:
- Proactively surfaces what matters via "nudges"
- Pre-made "recipes" for automated workflows (health, productivity, finance, scheduling)
- Integrates Gmail, Google Calendar, Outlook, Notion, Linear
- No app to install -- operates over existing messaging

**Key Pattern**: Frictionless delivery channel (text messaging) + recipe-based automation + contextual nudges. The delivery channel matters as much as the intelligence.

### 3.5 Friend -- Always-Listening AI Companion

**What**: $99 AI necklace, always listening via Bluetooth. No subscription.

**Proactive Mechanism**: "When connected via Bluetooth, your friend is always listening and forming their own internal thoughts." Proactively sends messages based on overheard context.

**Key Pattern**: Continuous ambient sensing -> internal thought formation -> selective message delivery. Similar to the "Inner Thoughts" academic framework.

---

## 4. Key Patterns & Architectures

### 4.1 The Heartbeat Pattern (Canonical Proactive Architecture)

The most widely adopted pattern across all projects studied:

```
┌─────────────┐
│  Scheduler   │ (cron / interval / event trigger)
└──────┬──────┘
       │ wake
       v
┌─────────────┐
│   Observe    │ (collect signals from data sources)
└──────┬──────┘
       │ context packet
       v
┌─────────────┐
│   Reason     │ (LLM evaluates: should I act?)
└──────┬──────┘
       │ decision + confidence
       v
┌─────────────┐
│    Act       │ (execute if threshold met, else HEARTBEAT_OK)
└──────┬──────┘
       │ log result
       v
┌─────────────┐
│   Sleep      │ (calculate next wake interval)
└─────────────┘
```

**Implementation Details**:
- Default interval: 30 min for task checks, 4 hrs for deep work, daily for summaries
- Each cycle passes: action history (what agent did last cycle), current context, task queue
- State persisted between cycles to prevent duplicate actions
- Cooldown periods enforced per action type

---

### 4.2 The "When to Interrupt" Decision Framework

Synthesized from all research, a multi-factor decision gate:

```
Proactive Score = f(urgency, relevance, confidence, user_state, history)

Where:
  urgency      = time-sensitivity of the information (0-1)
  relevance    = semantic match to user's active goals/interests (0-1)
  confidence   = system's certainty in the recommendation (0-1)
  user_state   = current cognitive load estimate (0-1, inverted)
  history      = recency of last interruption (decay factor)

Action if Proactive Score >= threshold (configurable, default ~0.6)
```

**Four-Level Response Ladder** (escalating intrusiveness):

| Level | Action | When to Use |
|---|---|---|
| Silent | Log internally only | Score < 0.3 |
| Queue | Save for next natural interaction | 0.3 <= Score < 0.5 |
| Notify | Ambient notification (badge, subtle indicator) | 0.5 <= Score < 0.7 |
| Interrupt | Active message/suggestion | Score >= 0.7 |

**Best Moments to Interrupt** (from field study data):
- After task completion (commit, save, send)
- When returning from absence
- At natural transition points (topic change, session start)
- When user explicitly enters "review" mode

**Worst Moments to Interrupt**:
- Mid-task / deep focus
- Immediately after declining a previous suggestion
- When user is in a hurry (short rapid interactions)

---

### 4.3 Signal Sources to Monitor

Collected from all projects and research:

| Signal Category | Examples | Update Frequency |
|---|---|---|
| **Calendar** | Upcoming meetings, schedule changes, free slots | Real-time events |
| **Messages** | Unread messages, conversation threads, mentions | Real-time |
| **Tasks** | Overdue items, approaching deadlines, blocked tasks | Heartbeat cycle |
| **Content** | New documents, file changes, shared resources | Event-driven |
| **External** | Weather, news, stock prices, API responses | Periodic polling |
| **Behavioral** | Activity patterns, response times, topic interests | Accumulated |
| **Temporal** | Time of day, day of week, proximity to events | Continuous |
| **Environmental** | Location, device state, network status | Event-driven |

---

### 4.4 Avoiding Notification Fatigue -- Design Principles

Synthesized from all research and products:

**Principle 1: Earn Trust Incrementally**
- Start at Level 1 (notification) for all new proactive features
- Graduate to higher levels only after demonstrated accuracy
- One bad proactive message erodes more trust than ten good ones build

**Principle 2: Learn from Behavior, Not Stated Preferences**
- Track: accepted, dismissed, modified, ignored
- Behavioral signals outperform self-reported preferences (61.3% vs 57.7%)
- Implicit feedback > explicit configuration

**Principle 3: Implement Cooldown and Rate Limiting**
- Per-category rate limits (max N notifications per hour per type)
- Global daily budget (total proactive messages per day)
- Exponential backoff on repeated dismissals
- "Do not disturb" detection from calendar/status

**Principle 4: Bundle and Batch**
- Aggregate multiple low-priority items into a single digest
- Daily morning briefing > 15 individual notifications throughout the day
- Natural batching points: morning, lunch, end of day

**Principle 5: Make Every Proactive Message Actionable**
- Include a clear action the user can take immediately
- "Reply" / "Snooze" / "Dismiss" / "Don't show this type again"
- Each interaction is a feedback signal

**Principle 6: Explain the Trigger**
- Always surface *why* this message appeared now
- "Because you have a meeting with X in 30 minutes and haven't reviewed the agenda"
- Transparency builds trust; mystery breeds annoyance

---

### 4.5 The Autonomy Dial (UX Pattern)

From Smashing Magazine's practical UX guide for agentic AI:

| Setting | Description |
|---|---|
| **Observe & Suggest** | Notifications only, no proposals |
| **Plan & Propose** | Agent creates plans; user reviews before action |
| **Act with Confirmation** | Familiar tasks get final go/no-go approval |
| **Act Autonomously** | Pre-approved tasks execute, then notify user |

**Companion Patterns**:
- **Intent Preview**: "Here's what I'm about to do. OK?"
- **Confidence Signal**: Visual indicator of agent's certainty
- **Action Audit**: Persistent log with undo capability
- **Escalation Pathway**: Graceful handoff when uncertain

**Target Metrics**:
- Intent Preview: >85% acceptance rate
- Autonomy Dial: Monitor setting churn for volatility
- Action Audit: <5% reversion rate

---

### 4.6 Memory Architecture for Proactive Systems

Synthesized optimal architecture from Zep, PASK, OpenClaw, and ZhiWei's existing design:

```
┌───────────────────────────────────────────┐
│          User Memory (L0 / Cache)          │
│  Stable traits, preferences, schedule      │
│  Access: Zero-latency (always in context)  │
├───────────────────────────────────────────┤
│       Working Memory (L1 / Session)        │
│  Current session state, active goals       │
│  Access: Immediate (context window)        │
├───────────────────────────────────────────┤
│       Episodic Memory (L2 / Recent)        │
│  Daily logs, recent interactions           │
│  Access: Fast retrieval (indexed)          │
├───────────────────────────────────────────┤
│      Semantic Memory (L3 / Knowledge)      │
│  Facts, relationships, knowledge graph     │
│  Access: Semantic search + RAG             │
│  TEMPORAL: bi-temporal validity windows    │
├───────────────────────────────────────────┤
│     Procedural Memory (L4 / Skills)        │
│  How-to knowledge, learned procedures      │
│  Access: Pattern-matched retrieval         │
├───────────────────────────────────────────┤
│      Intent Memory (NEW / Persistent)      │
│  Active user goals with trigger conditions │
│  Monitored each heartbeat cycle            │
│  Auto-expires when fulfilled or stale      │
└───────────────────────────────────────────┘
```

The **Intent Memory** layer is the key addition for proactive behavior -- it stores user goals that persist beyond the current session and are actively monitored.

---

### 4.7 Feedback Loop Architecture

```
User Interaction
       │
       v
┌──────────────┐     ┌──────────────┐
│   Proactive   │────>│    User      │
│   Suggestion  │     │   Response   │
└──────────────┘     └──────┬───────┘
                            │
              ┌─────────────┼─────────────┐
              v             v             v
         [Accept]      [Dismiss]      [Modify]
              │             │             │
              v             v             v
     ┌────────────────────────────────────────┐
     │        Preference Learning Layer        │
     │  - Update preference dimensions         │
     │  - Adjust proactive threshold           │
     │  - Update category weights              │
     │  - Decay confidence on repeated dismiss │
     └────────────────────────────────────────┘
              │
              v
     ┌────────────────────────────────────────┐
     │         Next Proactive Cycle            │
     │  - Adjusted scoring                     │
     │  - Personalized timing                  │
     │  - Refined content                      │
     └────────────────────────────────────────┘
```

---

## 5. Recommended Architecture for ZhiWei's Proactive Engine

Based on this research, here is a synthesized architecture recommendation:

### Core Components

1. **Heartbeat Scheduler**: Cron-based with configurable intervals per task type (30 min default, 4 hr deep, daily digest). Uses Spring's `@Scheduled` or a dedicated scheduler.

2. **Signal Collector**: Gathers context from all registered signal sources (calendar, messages, tasks, external APIs). Produces a structured `ContextPacket`.

3. **Demand Detector** (fast, cheap): Lightweight evaluation -- should we even bother reasoning? Three outcomes: `SILENT`, `FAST_INTERVENTION`, `FULL_ASSISTANCE`. This saves LLM costs on most heartbeat cycles.

4. **Proactive Reasoner** (full LLM call): Only invoked when Demand Detector says so. Scores candidate actions on proactive score (1-5). Produces thought traces for transparency.

5. **Action Gate**: Compares proactive score against user's configured threshold and current context (time, state, recent interactions). Applies rate limiting and cooldown.

6. **Intent Registry**: Persistent store of active user goals with trigger conditions. Checked every heartbeat cycle. Auto-expires stale intents.

7. **Preference Learner**: Tracks accept/dismiss/modify signals. Updates five-dimensional preference vector. Adjusts thresholds per category over time.

8. **Delivery Engine**: Routes proactive messages through appropriate channel (Web SSE, desktop notification, WeChat Work, DingTalk, Feishu) with appropriate intrusiveness level.

### Data Flow

```
Heartbeat Timer fires
  -> Signal Collector gathers ContextPacket
  -> Demand Detector: SILENT? done. FAST? use cached context. FULL? proceed.
  -> Intent Registry: check active goals against new signals
  -> Proactive Reasoner: generate candidate + proactive score
  -> Action Gate: score >= threshold AND cooldown clear AND user available?
  -> Delivery Engine: route to appropriate channel at appropriate level
  -> Log attempt + await user response
  -> Preference Learner: update model from response
```

---

## Sources

### Academic Papers
- [Proactive Conversational AI Survey (ACM TOIS)](https://dl.acm.org/doi/10.1145/3715097)
- [Survey on Proactive Dialogue Systems (IJCAI 2023)](https://arxiv.org/abs/2305.02750)
- [Proactive Conversational Agents with Inner Thoughts (CHI 2025)](https://arxiv.org/abs/2501.00383)
- [ContextAgent: Context-Aware Proactive LLM Agents (NeurIPS 2025)](https://arxiv.org/abs/2505.14668)
- [Preference-Aligned Proactive Assistants (Feb 2026)](https://arxiv.org/abs/2602.04000)
- [PASK: Intent-Aware Proactive Agents with Long-Term Memory (Apr 2026)](https://arxiv.org/abs/2604.08000)
- [ProAgentBench: Evaluating Proactive Assistance (Feb 2026)](https://arxiv.org/abs/2602.04482)
- [Proactive Agent: Shifting from Reactive to Active (Oct 2024)](https://arxiv.org/abs/2410.12361)
- [Long-term Task-oriented Proactive Agent (Jan 2026)](https://arxiv.org/abs/2601.09382)
- [ProPerSim: Developing Proactive and Personalized Assistants (Sep 2025)](https://arxiv.org/abs/2509.21730)
- [Developer Interaction Patterns with Proactive AI (Jan 2026)](https://arxiv.org/html/2601.10253v1)
- [Redefining Proactivity in Information-Seeking Dialogue](https://arxiv.org/html/2410.15297v1)
- [Zep: Temporal Knowledge Graph for Agent Memory (Jan 2025)](https://arxiv.org/abs/2501.13956)
- [ProAgent: On-Demand Sensory Contexts (Dec 2024)](https://arxiv.org/abs/2512.06721)
- [Designing Proactive Context-Aware AI Chatbot for Long-Term Goals (CHI 2024)](https://dl.acm.org/doi/10.1145/3613905.3650912)
- [Proactive AI and Human-Computer Trust (CHI 2024 Workshop)](https://cui.acm.org/workshops/CHI2024/wp-content/uploads/2024/04/Utilizing-Human-Computer-Trust-for-Enhancing-Proactive-Dialogue.pdf)
- [When AI-Based Agents Are Proactive (BISE 2024)](https://link.springer.com/article/10.1007/s12599-024-00918-y)
- [Need Help? Designing Proactive AI Assistants for Programming (CHI 2025)](https://dl.acm.org/doi/10.1145/3706598.3714002)
- [Mem0: Production-Ready AI Agents with Scalable Memory](https://arxiv.org/abs/2504.19413)

### Open Source Projects
- [OpenClaw (GitHub, 68k+ stars)](https://github.com/openclaw/openclaw)
- [Mem0 (GitHub, 48k+ stars)](https://github.com/mem0ai/mem0)
- [Letta / MemGPT (GitHub)](https://github.com/letta-ai/letta)
- [Graphiti / Zep (GitHub)](https://github.com/getzep/graphiti)
- [Screenpipe (GitHub, 16.7k stars)](https://github.com/mediar-ai/screenpipe)
- [ProactiveAgent (GitHub)](https://github.com/leomariga/ProactiveAgent)
- [Leon AI (GitHub)](https://github.com/leon-ai/leon)
- [OpenClaw Super Proactive Agent Skill](https://dev.to/aloycwl/understanding-the-super-proactive-agent-skill-in-openclaw-features-architecture-and-how-to-use-it-2om6)
- [Personal AI Infrastructure](https://github.com/danielmiessler/Personal_AI_Infrastructure)
- [ProactiveDialogues Paper Reading List](https://github.com/dengyang17/ProactiveDialogues)

### Architecture & Design Guides
- [Heartbeat Pattern for Proactive AI (MindStudio)](https://www.mindstudio.ai/blog/agentic-os-heartbeat-pattern-proactive-ai-agent)
- [What Is Proactive AI (Autonomous.ai)](https://www.autonomous.ai/ourblog/proactive-ai)
- [From Reactive to Proactive AI Agents (Medium)](https://medium.com/@manuedavakandam/from-reactive-to-proactive-how-to-build-ai-agents-that-take-initiative-10afd7a8e85d)
- [Proactive AI Agents Architecture (OpenClaw)](https://www.joumenharzli.com/blog/proactive-ai-agents-the-architecture-behind-openclaw/)
- [OpenClaw Architecture Deep Dive](https://openclaw.design/learn/how-openclaw-works)
- [Designing Agentic AI UX Patterns (Smashing Magazine)](https://www.smashingmagazine.com/2026/02/designing-agentic-ai-practical-ux-patterns/)
- [Proactive AI Agent Guide (Emilin Karlsson)](https://www.emilingemarkarlsson.com/blog/proactive-ai-agents-guide-2025/)
- [Google Assistant Proactive Features (Google Blog)](https://blog.google/products/assistant/stay-top-your-day-proactive-help-your-assistant/)
- [Mem0 vs Letta Comparison (Vectorize)](https://vectorize.io/articles/mem0-vs-letta)
- [People + AI Research: Feedback & Controls (Google PAIR)](https://pair.withgoogle.com/chapter/feedback-controls/)

### Commercial Products
- [Poke AI (TechCrunch)](https://techcrunch.com/2026/04/08/poke-makes-ai-agents-as-easy-as-sending-a-text/)
- [Friend AI Wearable (TechCrunch)](https://techcrunch.com/2024/07/30/friend-is-an-ai-companion-backed-by-founders-of-solana-perplexity-and-zfellows/)
- [Apple Intelligence 2026 (AppleMagazine)](https://applemagazine.com/apple-intelligence-2026-deep-dive/)
- [Microsoft Copilot Proactive Prompts (Microsoft Learn)](https://learn.microsoft.com/en-us/dynamics365/release-plan/2024wave2/service/dynamics365-customer-service/view-copilot-generated-proactive-prompts-insights)
- [Proactive AI in 2026 (AlphaSense)](https://www.alpha-sense.com/resources/research-articles/proactive-ai/)
