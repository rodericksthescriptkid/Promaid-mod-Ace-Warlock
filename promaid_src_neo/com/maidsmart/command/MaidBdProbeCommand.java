package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.bd.MaidBdCompat;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 超越维度（BeyondDimensions）兼容的**诊断探针**——只读，先回答"通不通"，再谈"存取"。
 *
 * <pre>
 *   /maid_smart bd_probe [女仆]                —— 反射是否正常、她主人有没有网络、主网络里都有什么
 *   /maid_smart bd_query &lt;物品id&gt; [女仆]      —— 主人的网络里这个物品有多少（O(1)，模拟取一次）
 * </pre>
 *
 * <p>为什么先做只读：维度的网络是**按玩家**的（入口全是 {@code Player}，方块侧才有 capability），
 * 所以女仆能用的是**她主人的**网络；"她主人到底有没有网络、网络里有什么格式的 key"这件事
 * 得先在实机里看一眼，才能决定存取那一层的形状。
 */
public final class MaidBdProbeCommand {

    private MaidBdProbeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("bd_probe")
                        .executes(ctx -> probe(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> probe(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_query")
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .executes(ctx -> query(ctx.getSource(), null,
                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item")))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(ctx -> query(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item")))))));
    }

    private static int probe(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("超越维度反射：" + (MaidBdCompat.available() ? "正常" : "没解析到（没装或版本类名不符）"));
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null) {
            lines.add("她没有主人 —— 网络是按玩家挂的，所以这条路只能在单机/带主人的服务器上用");
        } else {
            lines.add("主人：" + owner.getName().getString()
                    + " 网络数=" + MaidBdCompat.netCount(owner)
                    + " 有网络=" + MaidBdCompat.hasAnyNet(owner));
            Object net = MaidBdCompat.primaryNet(owner);
            lines.add("主网络：" + MaidBdCompat.netDescribe(net)
                    + " 内容种类=" + MaidBdCompat.contentKinds(net));
            for (MaidBdCompat.Line l : MaidBdCompat.contents(net, 12)) {
                lines.add("  · " + l.id() + " × " + l.amount());
            }
        }
        for (String s : lines) {
            src.sendSuccess(() -> Component.literal(s), false);
            PromaidLog.log("超越维度探针", maid.getName().getString() + " " + s);
        }
        return 1;
    }

    private static int query(CommandSourceStack src, net.minecraft.world.entity.Entity picked, ResourceLocation rl) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String itemId = rl.toString();
        if (!BuiltInRegistries.ITEM.containsKey(rl)) {
            src.sendFailure(Component.literal("不认识的物品 id：" + itemId));
            return 0;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null) {
            src.sendFailure(Component.literal("她没有主人，没有可用的网络"));
            return 0;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        long n = MaidBdCompat.countOf(net, new ItemStack(BuiltInRegistries.ITEM.get(rl)));
        String line = "查询 " + itemId + " → " + (n < 0 ? "查询失败" : n + " 个");
        src.sendSuccess(() -> Component.literal(line), false);
        PromaidLog.log("超越维度探针", maid.getName().getString() + " " + line);
        return 1;
    }

    private static EntityMaid asMaid(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        if (picked instanceof EntityMaid m) {
            return m;
        }
        try {
            AABB box = src.getEntity() != null
                    ? src.getEntity().getBoundingBox().inflate(64.0D)
                    : new AABB(src.getPosition().x - 64, src.getPosition().y - 64, src.getPosition().z - 64,
                            src.getPosition().x + 64, src.getPosition().y + 64, src.getPosition().z + 64);
            return src.getLevel().getEntitiesOfClass(EntityMaid.class, box).stream().findFirst().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
