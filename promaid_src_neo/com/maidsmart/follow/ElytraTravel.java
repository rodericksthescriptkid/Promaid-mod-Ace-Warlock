package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.2.2 实测五百八十七【鞘翅赶路 · 会话与工具】——非战斗常态下，主人跑远了让她**飞过去**，
 * 而不是被 TLM 的跟随瞬移直接拽到身边。
 *
 * 【为什么要"会话"这种东西】TLM 的 {@code MaidFollowOwnerTask#start} 在
 * 「离主人 > 工作范围半径 + 2」时直接 {@code teleportToOwner}；它每 tick 重新启动
 * （不覆写 canStillUse，启动即结束），所以**够远的每一 tick 都会尝试瞬移**。想让她飞，
 * 就必须在那一刻把瞬移拦下来，并且**有始有终**：飞完了、超时了、掉燃料了、遇到危险了，
 * 都要立刻交还瞬移，否则她会卡在原地（这是本功能最大的风险点，所有兜底都围绕它）。
 *
 * 【分工】
 * <ul>
 *   <li>{@link #interceptTeleport}：给 {@code MaidTeleportPreserveMixin} 调用——判定够不够
 *       资格飞、够就把「起飞请求」挂上并返回 true（= 跳过本次瞬移）；</li>
 *   <li>{@link ElytraTravelBehavior}：消费请求 → 建立会话 → 每 tick 操纵滑翔 → 结束会话。</li>
 * </ul>
 *
 * 【绝对安全线】请求挂起超过 {@link #REQUEST_TTL} 还没被行为消费（例如行为被更高优先级的
 * 东西压住、或她的脑冻结了），拦截立刻失效 → 下一 tick 恢复正常瞬移。宁可瞬移，不会卡住。
 */
public final class ElytraTravel {

    private ElytraTravel() {
    }

    /** 正在赶路：UUID → 会话开始的 gameTime */
    private static final Map<UUID, Long> SESSION = new HashMap<>();
    /** 起飞请求：UUID → 请求时刻的 gameTime（行为消费后清除） */
    private static final Map<UUID, Long> REQUEST = new HashMap<>();
    /** 落地后的冷却：UUID → 在这个 gameTime 之前不再起飞（防抖） */
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    /** 烟花推进冷却：UUID → 下一次可放烟花的 gameTime */
    private static final Map<UUID, Long> FIREWORK_READY = new HashMap<>();
    /** 本会话已到达的最近距离（没进展判定用）：UUID → 最近距离 */
    private static final Map<UUID, Double> BEST_DIST = new HashMap<>();
    /** 没进展判定的时间戳：UUID → 上一次刷新最近距离的 gameTime */
    private static final Map<UUID, Long> BEST_AT = new HashMap<>();
    /** 调试目标（/maid_smart elytra_goto）：UUID → 目标点（有它时不追主人） */
    private static final Map<UUID, Vec3> DEBUG_TARGET = new HashMap<>();

    /** 请求挂起上限（tick）：这么久没被行为消费就放弃拦截，恢复正常瞬移 */
    private static final int REQUEST_TTL = 60;

    /** 到达判定半径（格）：进到这个圈里就算赶到了，收鞘翅交还跟随 */
    public static final double LAND_DISTANCE = 6.0;

    private static long now(EntityMaid maid) {
        return maid.level().getGameTime();
    }

    /* ---------------- 会话/请求状态 ---------------- */

    public static boolean isTraveling(EntityMaid maid) {
        return maid != null && SESSION.containsKey(maid.getUUID());
    }

    public static long sessionStart(EntityMaid maid) {
        Long t = SESSION.get(maid.getUUID());
        return t == null ? 0L : t;
    }

    /** 挂起一次起飞请求（手动触发：{@code /maid_smart elytra_goto}） */
    public static void request(EntityMaid maid) {
        if (maid != null) {
            REQUEST.put(maid.getUUID(), now(maid));
        }
    }

    /** 请求是否还新鲜（行为消费窗口：{@link #REQUEST_TTL}） */
    public static boolean hasFreshRequest(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        Long t = REQUEST.get(maid.getUUID());
        return t != null && now(maid) - t <= REQUEST_TTL;
    }

    /** 行为消费请求：建立会话（请求挂起时间随"会话开始"重新计，见 tick 的超时口径）。 */
    public static void begin(EntityMaid maid) {
        UUID id = maid.getUUID();
        REQUEST.remove(id);
        SESSION.put(id, now(maid));
        BEST_DIST.remove(id);
        BEST_AT.remove(id);
    }

    /** 结束会话（幂等）：清状态 + 上冷却 + 记一条日志。 */
    public static void end(EntityMaid maid, String reason) {
        if (maid == null) {
            return;
        }
        UUID id = maid.getUUID();
        boolean was = SESSION.remove(id) != null;
        REQUEST.remove(id);
        FIREWORK_READY.remove(id);
        BEST_DIST.remove(id);
        BEST_AT.remove(id);
        int cd = 60;
        try {
            cd = Math.max(0, MaidSmartConfig.MISC_ELYTRA_TRAVEL_COOLDOWN.get());
        } catch (Throwable ignored) {
        }
        COOLDOWN.put(id, now(maid) + cd);
        if (was) {
            PromaidLog.log("鞘翅赶路", PromaidLog.nameOf(maid) + " 结束赶路（" + reason + "）");
        }
    }

    /* ---------------- 瞬移拦截（mixin 入口） ---------------- */

    /**
     * 要不要把这一次瞬移拦下来、改成飞过去？
     *
     * @return true = 跳过本次瞬移（已在赶路，或刚挂上起飞请求）
     */
    public static boolean interceptTeleport(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        try {
            if (!MaidSmartConfig.MISC_ELYTRA_TRAVEL.get()) {
                return false;
            }
            long t = now(maid);
            // 已在赶路：继续拦（会话自己会结束，见 ElytraTravelBehavior.tick 的各条兜底）
            if (isTraveling(maid)) {
                return true;
            }
            // 冷却中：放行瞬移（防抖——刚落地又被主人甩开时不再立刻起飞）
            Long cd = COOLDOWN.get(maid.getUUID());
            if (cd != null && t < cd) {
                return false;
            }
            // 请求挂起超时（行为一直没来消费）：放弃拦截，恢复正常瞬移
            Long req = REQUEST.get(maid.getUUID());
            if (req != null && t - req > REQUEST_TTL) {
                REQUEST.remove(maid.getUUID());
                return false;
            }
            if (!canTravel(maid)) {
                return false;
            }
            REQUEST.put(maid.getUUID(), t);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 资格判定 ---------------- */

    /**
     * 她**能不能**用鞘翅赶路（装备 + 燃料 + 状态）。
     *
     * 不含距离判定——距离由调用点决定（mixin 挂在"TLM 要瞬移的那一刻"，那本身就是
     * 「超过工作范围半径 + 2」的等价条件）。
     */
    public static boolean canTravel(EntityMaid maid) {
        if (maid == null || !maid.isAlive()) {
            return false;
        }
        try {
            // 空袭模式自己会飞，别抢
            if (MaidFlightKit.isFlightTask(maid)) {
                return false;
            }
            // 停放/骑乘/睡觉/水/岩浆：不飞（水里滑翔没意义，岩浆是找死）
            if (maid.isPassenger() || maid.isMaidInSittingPose() || maid.isSleeping()
                    || maid.isInWater() || maid.isInLava()) {
                return false;
            }
            // 脑冻结 / 守家：不做跟随，也不赶路
            if (!maid.canBrainMoving() || maid.isHomeModeEnable()) {
                return false;
            }
            // 有攻击目标（挨打/追击中）：先打完再说，别背对敌人起飞
            if (maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                    .isPresent()) {
                return false;
            }
            // 刚被谁打过（3 秒内）：同理让战斗/自保先处理
            if (maid.getLastHurtByMob() != null && maid.getLastHurtByMobTimestamp() + 60 > maid.tickCount) {
                return false;
            }
            // 鞘翅（穿在身上/手上/背包里都算，"穿"这一步由行为负责）
            if (!MaidFlightKit.hasElytra(maid)) {
                return false;
            }
            // 燃料：烟花（稳）或"提供高度"的位移法术（没烟花的整合包也能用）
            boolean fw = MaidSmartConfig.MISC_ELYTRA_TRAVEL_FIREWORK.get() && MaidFlightKit.hasFirework(maid);
            boolean sp = MaidSmartConfig.MISC_ELYTRA_TRAVEL_SPELL.get() && MaidFlightKit.hasClimbSpell(maid);
            return fw || sp;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 目标点 ---------------- */

    public static void setDebugTarget(EntityMaid maid, Vec3 pos) {
        DEBUG_TARGET.put(maid.getUUID(), pos);
    }

    public static void clearDebugTarget(EntityMaid maid) {
        DEBUG_TARGET.remove(maid.getUUID());
    }

    public static Vec3 debugTarget(EntityMaid maid) {
        return DEBUG_TARGET.get(maid.getUUID());
    }

    /**
     * 本次赶路的瞄准点：调试目标优先，否则主人身上方一点。
     *
     * 主人比她高很多时抬高瞄准点（先把高度换出来），同高时只抬 2 格（贴着地形飞过去）。
     */
    public static Vec3 travelTarget(EntityMaid maid) {
        Vec3 dbg = debugTarget(maid);
        if (dbg != null) {
            return dbg;
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            return null;
        }
        double dy = owner.getY() - maid.getY();
        double lift = dy > 4.0 ? 6.0 : (dy < -4.0 ? 0.0 : 2.0);
        return new Vec3(owner.getX(), owner.getY() + lift, owner.getZ());
    }

    /* ---------------- 推进辅助（与空袭同源） ---------------- */

    public static boolean fireworkReady(EntityMaid maid) {
        Long ready = FIREWORK_READY.get(maid.getUUID());
        return ready == null || now(maid) >= ready;
    }

    public static void markFirework(EntityMaid maid, int intervalTicks) {
        FIREWORK_READY.put(maid.getUUID(), now(maid) + Math.max(10, intervalTicks));
    }

    /**
     * 没进展判定：本会话最近距离有没有推进过。
     *
     * @return true = 已经 {@code windowTicks} 没推进（撞地形/被墙挡住/主人在洞里），该收工
     */
    public static boolean stalled(EntityMaid maid, double dist, int windowTicks) {
        UUID id = maid.getUUID();
        Double best = BEST_DIST.get(id);
        long t = now(maid);
        if (best == null || dist < best - 1.0) {
            BEST_DIST.put(id, best == null ? dist : Math.min(best, dist));
            BEST_AT.put(id, t);
            return false;
        }
        Long at = BEST_AT.get(id);
        return at != null && t - at > windowTicks;
    }
}
