# Proactive Engine Phase 3: Active Behavior — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add autonomy levels (A/B/C per behavior with independent toggles), trust upgrade mechanism, and 4 new behavior plugins (info supplement, context prep, daily/weekly report, task execution).

**Architecture:** `AutonomyLevel` (A=notify, B=suggest, C=execute) stored per-behavior in `proactive_behavior_autonomy` table. `DecisionGate` checks autonomy level before delivery — A caps at NOTIFY, B allows suggestions, C enables `execute()`. Trust upgrade: consecutive positive feedback → suggest level promotion (user must confirm). Four new plugins follow established `ProactiveBehavior` pattern.

**Tech Stack:** Java 22, Spring Boot, SQLite (Flyway), JUnit 5, Mockito

---

## Tasks

### Task 1: Autonomy Level Infrastructure (V5 + AutonomyLevel + AutonomyConfig + AutonomyRepository)
### Task 2: DecisionGate Autonomy Integration
### Task 3: InfoSupplementBehavior — knowledge base updates relevant to user interests
### Task 4: ContextPrepBehavior — upcoming event context preparation
### Task 5: ReportBehavior — time-triggered daily/weekly summary
### Task 6: TaskExecutionBehavior — intent trigger condition → workflow execution
### Task 7: Trust Upgrade Mechanism
### Task 8: Auto-Configuration + Regression Verification
