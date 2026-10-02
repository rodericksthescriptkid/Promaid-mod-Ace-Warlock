package com.maidsmart.command;

import com.maidsmart.bd.MaidBdRules;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 实测 G-9【超越维度·规则名单的玩家入口】——改 {@code config/promaid_bd_rules.json}，改完即存盘。
 *
 * <pre>
 *   /maid_smart bd_rule list                       看当前规则
 *   /maid_smart bd_rule move   &lt;条目&gt;              一定搬（条目 = minecraft:coal 或 tag:c:ores）
 *   /maid_smart bd_rule keep   &lt;条目&gt;              一定不搬（例如 minecraft:golden_carrot）
 *   /maid_smart bd_rule keepN  &lt;个数&gt; &lt;条目&gt;       她背包里最多留几个，多的搬走
 *   /maid_smart bd_rule remove &lt;条目&gt;              删掉某条规则
 * </pre>
 *
 * <p>条目的两种写法：<b>裸物品 id</b>（等于 {@code item:}）与 <b>{@code tag:命名空间:路径}</b>。
 * 用了 {@code greedyString}，所以带斜杠的标签写法也能直接敲，不用加引号。
 */
public final class MaidBdRuleCommand {

    private MaidBdRuleCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("bd_rule")
                        .executes(ctx -> show(ctx.getSource()))
                        .then(Commands.literal("list").executes(ctx -> show(ctx.getSource())))
                        .then(Commands.literal("move")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addMove(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.literal("keep")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addKeep(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.literal("keepN")
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 1000000))
                                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        MaidBdRules.setKeepN(StringArgumentType.getString(ctx, "entry"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.remove(StringArgumentType.getString(ctx, "entry"))))))));
    }

    private static int show(CommandSourceStack src) {
        List<String> lines = MaidBdRules.describe();
        for (String s : lines) {
            src.sendSuccess(() -> Component.literal(s), false);
            PromaidLog.log("超越维度规则", s);
        }
        return 1;
    }

    private static int say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("超越维度规则", msg);
        return 1;
    }
}
