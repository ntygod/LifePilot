package com.lifepilot.interaction.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;

import java.util.List;

/**
 * CLI Tab 补全器 — 基于 JLine 3 的命令补全。
 *
 * <p>补全范围包括所有顶层命令（chat/todo/schedule/habit/llm/mcp/skill）、
 * 各命令的子命令，以及特殊命令（/exit、/quit、/new）。
 * 内部使用 {@link AggregateCompleter} 组合多个 {@link ArgumentCompleter}，
 * 每个 ArgumentCompleter 对应一条命令路径。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CliCompleter implements Completer {

    /** 内部聚合补全器，组合所有命令路径的补全逻辑。 */
    private final AggregateCompleter delegate;

    public CliCompleter() {
        this.delegate = new AggregateCompleter(buildCompleters());
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        delegate.complete(reader, line, candidates);
    }

    /**
     * 构建所有命令路径的补全器列表。
     *
     * @return 补全器列表
     */
    private List<Completer> buildCompleters() {
        return List.of(
                // chat（无子命令）
                new ArgumentCompleter(new StringsCompleter("chat"), NullCompleter.INSTANCE),

                // todo list | add | done | delete
                new ArgumentCompleter(
                        new StringsCompleter("todo"),
                        new StringsCompleter("list", "add", "done", "delete"),
                        NullCompleter.INSTANCE
                ),

                // schedule list | add | today | tomorrow
                new ArgumentCompleter(
                        new StringsCompleter("schedule"),
                        new StringsCompleter("list", "add", "today", "tomorrow"),
                        NullCompleter.INSTANCE
                ),

                // habit list | checkin | status
                new ArgumentCompleter(
                        new StringsCompleter("habit"),
                        new StringsCompleter("list", "checkin", "status"),
                        NullCompleter.INSTANCE
                ),

                // llm list | add | remove | enable | disable | test
                new ArgumentCompleter(
                        new StringsCompleter("llm"),
                        new StringsCompleter("list", "add", "remove", "enable", "disable", "test"),
                        NullCompleter.INSTANCE
                ),

                // mcp list | add | remove | test
                new ArgumentCompleter(
                        new StringsCompleter("mcp"),
                        new StringsCompleter("list", "add", "remove", "test"),
                        NullCompleter.INSTANCE
                ),

                // skill list | info | reload
                new ArgumentCompleter(
                        new StringsCompleter("skill"),
                        new StringsCompleter("list", "info", "reload"),
                        NullCompleter.INSTANCE
                ),

                // 特殊命令：/exit、/quit、/new
                new ArgumentCompleter(
                        new StringsCompleter("/exit", "/quit", "/new"),
                        NullCompleter.INSTANCE
                )
        );
    }
}
