package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 主动提醒上下文动作 Bandit。
 *
 * <p>使用轻量 LinUCB 在线性特征空间上做动作选择，
 * 当前仅用于 {@code SOFT_PUSH / NORMAL_PUSH} 之间的上下文决策。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderActionContextualBandit {

    private static final int FEATURE_DIMENSION = 20;

    private final float explorationAlpha;
    private final int minExamples;
    private final int minActionSamples;
    private final double ridgeLambda;

    public ReminderActionContextualBandit() {
        this(0.18f, 16, 3, 1.0d);
    }

    public ReminderActionContextualBandit(float explorationAlpha,
                                          int minExamples,
                                          int minActionSamples,
                                          double ridgeLambda) {
        this.explorationAlpha = Math.max(0.0f, explorationAlpha);
        this.minExamples = Math.max(1, minExamples);
        this.minActionSamples = Math.max(1, minActionSamples);
        this.ridgeLambda = ridgeLambda > 0.0d ? ridgeLambda : 1.0d;
    }

    @Nullable
    public Policy fit(List<ReminderActionTrainingExample> examples,
                      List<ReminderAction> requiredActions) {
        if (examples == null || examples.isEmpty() || requiredActions == null || requiredActions.isEmpty()) {
            return null;
        }
        if (examples.size() < minExamples) {
            return null;
        }

        Map<ReminderAction, ArmState> states = new EnumMap<>(ReminderAction.class);
        for (ReminderAction action : requiredActions) {
            states.put(action, new ArmState(FEATURE_DIMENSION, ridgeLambda));
        }

        int usableExamples = 0;
        for (ReminderActionTrainingExample example : examples) {
            ArmState state = states.get(example.action());
            if (state == null) {
                continue;
            }
            state.observe(encode(example), example.reward());
            usableExamples++;
        }
        if (usableExamples < minExamples) {
            return null;
        }
        boolean enoughPerAction = requiredActions.stream()
                .map(states::get)
                .allMatch(state -> state != null && state.sampleCount >= minActionSamples);
        if (!enoughPerAction) {
            return null;
        }
        return new Policy(states);
    }

    private double[] encode(ReminderActionTrainingExample example) {
        double[] features = new double[FEATURE_DIMENSION];
        features[0] = 1.0d;
        features[1] = example.finalScore();
        features[2] = example.evidenceScore();
        features[3] = example.timingScore();
        features[4] = example.urgencyScore();
        features[5] = example.userFitScore();
        features[6] = example.actionabilityScore();
        features[7] = 1.0d - example.duplicatePenalty();
        features[8] = 1.0d - example.fatiguePenalty();
        features[9] = clamp(example.topicRemindersSentToday() / 3.0d);
        features[10] = clamp(example.topicReadCount30d() / 5.0d);
        features[11] = clamp(example.topicActedCount30d() / 5.0d);
        features[12] = clamp(example.topicDismissedCount30d() / 5.0d);
        features[13] = clamp(example.topicSnoozedCount30d() / 5.0d);
        features[14] = clamp(example.topicNotRelevantCount30d() / 5.0d);
        fillTypeFeatures(features, example.candidateType());
        return features;
    }

    private double[] encode(ReminderCandidate candidate, ReminderTopicState state) {
        double[] features = new double[FEATURE_DIMENSION];
        features[0] = 1.0d;
        features[1] = candidate.finalScore();
        features[2] = candidate.evidenceScore();
        features[3] = candidate.timingScore();
        features[4] = candidate.urgencyScore();
        features[5] = candidate.userFitScore();
        features[6] = candidate.actionabilityScore();
        features[7] = 1.0d - candidate.duplicatePenalty();
        features[8] = 1.0d - candidate.fatiguePenalty();
        features[9] = clamp(state.remindersSentToday() / 3.0d);
        features[10] = clamp(state.readCount30d() / 5.0d);
        features[11] = clamp(state.actedCount30d() / 5.0d);
        features[12] = clamp(state.dismissedCount30d() / 5.0d);
        features[13] = clamp(state.snoozedCount30d() / 5.0d);
        features[14] = clamp(state.notRelevantCount30d() / 5.0d);
        fillTypeFeatures(features, candidate.type().name());
        return features;
    }

    private void fillTypeFeatures(double[] features, String candidateType) {
        ReminderCandidateType type = parseCandidateType(candidateType);
        if (type == null) {
            return;
        }
        switch (type) {
            case DUE_SOON -> features[15] = 1.0d;
            case COMMITMENT_GAP -> features[16] = 1.0d;
            case HABIT_WINDOW -> features[17] = 1.0d;
            case PREPARATION_WINDOW -> features[18] = 1.0d;
            case BEHAVIOR_ANOMALY -> features[19] = 1.0d;
        }
    }

    @Nullable
    private ReminderCandidateType parseCandidateType(String candidateType) {
        if (candidateType == null || candidateType.isBlank()) {
            return null;
        }
        try {
            return ReminderCandidateType.valueOf(candidateType);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    /**
     * 已训练好的策略。
     */
    public final class Policy {

        private final Map<ReminderAction, ArmState> states;

        private Policy(Map<ReminderAction, ArmState> states) {
            this.states = states;
        }

        public float score(ReminderCandidate candidate,
                           ReminderTopicState state,
                           ReminderAction action) {
            return estimate(candidate, state, action).optimisticReward();
        }

        public ReminderActionBanditEstimate estimate(ReminderCandidate candidate,
                                                     ReminderTopicState state,
                                                     ReminderAction action) {
            ArmState armState = states.get(action);
            if (armState == null) {
                return new ReminderActionBanditEstimate(
                        action,
                        Float.NEGATIVE_INFINITY,
                        0.0f,
                        Float.NEGATIVE_INFINITY,
                        Float.NEGATIVE_INFINITY,
                        0
                );
            }
            double[] x = encode(candidate, state);
            double[] theta = armState.theta();
            double expectedReward = dot(theta, x);
            double[] invTimesX = multiply(armState.inverse, x);
            double uncertainty = Math.sqrt(Math.max(0.0d, dot(x, invTimesX)));
            float expected = clamp((float) expectedReward);
            float uncertaintyScore = clamp((float) uncertainty);
            float optimistic = clamp((float) (expectedReward + explorationAlpha * uncertainty));
            float conservative = clamp((float) (expectedReward - explorationAlpha * uncertainty));
            return new ReminderActionBanditEstimate(
                    action,
                    expected,
                    uncertaintyScore,
                    optimistic,
                    conservative,
                    armState.sampleCount
            );
        }

        public int sampleCount(ReminderAction action) {
            ArmState armState = states.get(action);
            return armState != null ? armState.sampleCount : 0;
        }
    }

    private static final class ArmState {
        private final double[][] inverse;
        private final double[] b;
        private int sampleCount;

        private ArmState(int dimension, double ridgeLambda) {
            this.inverse = new double[dimension][dimension];
            this.b = new double[dimension];
            for (int i = 0; i < dimension; i++) {
                inverse[i][i] = 1.0d / ridgeLambda;
            }
        }

        private void observe(double[] x, float reward) {
            double[] invTimesX = multiply(inverse, x);
            double denominator = 1.0d + dot(x, invTimesX);
            if (denominator <= 1e-12d) {
                // 数值退化：跳过本次样本，不更新模型也不计数
                return;
            }
            for (int i = 0; i < inverse.length; i++) {
                for (int j = 0; j < inverse[i].length; j++) {
                    inverse[i][j] -= (invTimesX[i] * invTimesX[j]) / denominator;
                }
            }
            for (int i = 0; i < b.length; i++) {
                b[i] += reward * x[i];
            }
            sampleCount++;
        }

        private double[] theta() {
            return multiply(inverse, b);
        }
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[vector.length];
        for (int i = 0; i < matrix.length; i++) {
            double sum = 0.0d;
            for (int j = 0; j < vector.length; j++) {
                sum += matrix[i][j] * vector[j];
            }
            result[i] = sum;
        }
        return result;
    }

    private static double dot(double[] left, double[] right) {
        double sum = 0.0d;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
