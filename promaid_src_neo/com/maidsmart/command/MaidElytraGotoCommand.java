package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.follow.ElytraTravel;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * v1.2.2 实测五百八十七【鞘翅赶路 · 手动触发】：{@code /maid_smart elytra_goto <x> <y> <z> [女仆名]}
 * 让女仆穿鞘翅飞向指定坐标。
 *
 * 【为什么要有这个命令】
 * <ol>
 *   <li>排查用：无头测试服里没有真玩家，就没有"主人"，自动触发（TLM 要瞬移 / 空袭待机）无从发生，
 *       只能靠这个命令复现整条飞行链路；</li>
 *   <li>实用：想让她"飞去那边的高台/浮空岛"时不必先跑一趟再等她跟丢。
 *       她到位后自动收鞘翅、清目标，交还正常跟随。</li>
 * </ol>
 *
 * 不写女仆名 = 取"离目标点最近的一只"（64 格内）；写了名字（引号包起来）= 精确找那一只
 * （测试多只女仆时非常必要——否则很容易把命令下给旁边一只站桩的旧女仆）。
 */
public final class MaidElytraGotoCommand {

    private MaidElytraGotoCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .then(Commands.literal("elytra_goto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> gotoPos(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                        DoubleArgumentType.getDouble(ctx, "z"),
                                                        null))
                                                .then(Commands.argument("maid", StringArgumentType.string())
                                                        .executes(ctx -> gotoPos(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"),
                                                                StringArgumentType.getString(ctx, "maid")))))))));
    }

    private static int gotoPos(CommandSourceStack src, double x, double y, double z, String maidName) {
        try {
            ServerLevel level = src.getLevel();
            Vec3 pos = new Vec3(x, y, z);
            // 以命令执行点为中心找女仆（与 /maid_smart 其它子命令同口径：64 格）
            AABB box = new AABB(pos, pos).inflate(64.0);
            List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class, box,
                    m -> m.isAlive() && !m.isMaidInSittingPose());
            if (maidName != null) {
                String want = maidName.trim();
                maids = maids.stream().filter(m -> want.equals(com.maidsmart.tool.PromaidLog.nameOf(m)))
                        .toList();
                if (maids.isEmpty()) {
                    // 精确名没命中时退一步：名字里包含也算（自定义名可能带颜色码/前后缀）
                    maids = level.getEntitiesOfClass(EntityMaid.class, box,
                            m -> m.isAlive() && com.maidsmart.tool.PromaidLog.nameOf(m).contains(want));
                }
            }
            if (maids.isEmpty()) {
                src.sendFailure(Component.literal("§c目标点 64 格内没有可用女仆"
                        + (maidName == null ? "。" : "（名字：" + maidName + "）。")));
                return 0;
            }
            EntityMaid maid = maids.stream()
                    .min((a, b) -> Double.compare(a.distanceToSqr(pos), b.distanceToSqr(pos)))
                    .orElse(null);
            if (maid == null) {
                src.sendFailure(Component.literal("§c没有找到女仆。"));
                return 0;
            }
            // 与行为同口径：她如果挂着空袭任务，按"空袭待机"那一路判定（要求确实没有敌人）
            boolean inFlightTask = com.maidsmart.combat.MaidFlightKit.isFlightTask(maid);
            String blocked = ElytraTravel.travelBlockReason(maid, inFlightTask);
            if (blocked != null) {
                src.sendFailure(Component.literal("§c" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 现在飞不了：" + blocked
                        + "（需要：可用鞘翅 + 烟花或「提供高度」位移法术）"));
                return 0;
            }
            ElytraTravel.setDebugTarget(maid, pos);
            ElytraTravel.request(maid);
            src.sendSuccess(() -> Component.literal("§a" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 开始鞘翅赶路 → " + (int) x + " " + (int) y + " " + (int) z), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§celytra_goto 失败：" + t));
            return 0;
        }
    }
}
