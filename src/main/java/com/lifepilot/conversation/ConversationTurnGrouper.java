package com.lifepilot.conversation;

import com.lifepilot.interaction.web.repository.ChatMessageRepository.ChatMessageRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 顺序消息切分器。
 *
 * <p>一轮对话以 user 消息开始，后续直到下一个 user 之前的消息都归入同一轮。
 * 末尾仅包含 user 且没有回复的未完成轮次会被排除。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public final class ConversationTurnGrouper {

    private ConversationTurnGrouper() {
    }

    public static List<List<ChatMessageRow>> groupCompleteTurns(List<ChatMessageRow> rows) {
        var turns = new ArrayList<List<ChatMessageRow>>();
        var current = new ArrayList<ChatMessageRow>();
        boolean hasReply = false;

        for (var row : rows) {
            String role = normalizeRole(row.role());
            if ("user".equals(role)) {
                if (!current.isEmpty() && hasReply) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
                hasReply = false;
                continue;
            }

            if (current.isEmpty()) {
                continue;
            }

            current.add(row);
            hasReply = true;
        }

        if (!current.isEmpty() && hasReply) {
            turns.add(List.copyOf(current));
        }

        return List.copyOf(turns);
    }

    public static List<ChatMessageRow> flattenRecentCompleteTurns(List<ChatMessageRow> rows, int turnLimit) {
        if (rows.isEmpty() || turnLimit <= 0) {
            return List.of();
        }
        var turns = groupCompleteTurns(rows);
        if (turns.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, turns.size() - turnLimit);
        var selected = turns.subList(fromIndex, turns.size());
        var flattened = new ArrayList<ChatMessageRow>();
        for (var turn : selected) {
            flattened.addAll(turn);
        }
        return List.copyOf(flattened);
    }

    private static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }
}
