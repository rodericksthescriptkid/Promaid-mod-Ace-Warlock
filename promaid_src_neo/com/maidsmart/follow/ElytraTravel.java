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

    /** 诊断：同理由的限频（UUID → 上次理由 / 上次时间） */
    private static final Map<UUID, String> LAST_BLOCK_REASON = new HashMap<>();
    private static final Map<UUID, Long> LAST_BLOCK_AT = new HashMap<>();

    /**
     * v1.2.2 实测五百八十九【诊断日志】"她该飞却没飞"时把理由记一条。
     *
     * 限频：**理由变了立刻记**；理由没变则每 600 tick（30 秒）最多一条——不然一个永远飞不起来的
     * 女仆（比如没装鞘翅）会把日志刷满。
     */
    public static void logBlocked(EntityMaid maid, String tag, String reason) {
        if (maid == null || reason == null) {
            return;
        }
        try {
            UUID id = maid.getUUID();
            long t = now(maid);
            String last = LAST_BLOCK_REASON.get(id);
            Long at = LAST_BLOCK_AT.get(id);
            if (reason.equals(last) && at != null && t - at < 600) {
                return;
            }
            LAST_BLOCK_REASON.put(id, reason);
            LAST_BLOCK_AT.put(id, t);
            PromaidLog.log("鞘翅赶路·待机", PromaidLog.nameOf(maid) + " 没起飞：" + tag + " → " + reason);
        } catch (Throwable ignored) {
        }
    }

    private static long now(EntityMaid maid) {
        return maid.level().getGameTime();
    }

    /* ---------------- 会话/请求状态 ---------------- */

    /** 赶路开始时胸甲位鞘翅的耐久快照：UUID → 起始损伤值（"不消耗耐久"选项用） */
    private static final Map<UUID, Integer> DURA_SNAPSHOT = new HashMap<>();

    /**
     * v1.2.2 实测五百九十【可选：赶路不消耗鞘翅耐久】。
     *
     * 开着的会话里，每 tick 把胸甲位鞘翅的损伤值**归位到起飞时的数值**——等价于"这段飞行的磨损
     * 不算"，但她永远碰不到损坏点（原版每 20 tick 掉一点，归位每 tick 都做，所以到不了上限）。
     *
     * 【为什么不用 mixin】最初写的是 {@code @Redirect} 拦 `LivingEntity#updateFallFlying` 里那处
     * `ItemStack.hurtAndBreak`（javap 实证的位置，语义最精确），但**本地构建没有作者的 mixin refmap**，
     * 原版类上的注入匹配不到目标 → 服务端直接在启动阶段崩掉（实测）。改动语义完全等价的替代方案后，
     * 既不依赖 refmap、也不碰任何原版类。
     *
     * 副作用（已知并接受）：赶路期间若她胸甲挨了别的损伤，也会被一起归位。飞行途中她要么没敌人、
     * 要么赶路会立刻收工（tick 里有"出现敌人"兜底），所以这个窗口很小。
     */
    public static void freezeElytraDurability(EntityMaid maid) {
        try {
            if (maid == null || !MaidSmartConfig.MISC_ELYTRA_TRAVEL_NO_DURABILITY.get()) {
                return;
            }
            net.minecraft.world.item.ItemStack chest =
                    maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            if (chest.isEmpty() || !MaidFlightKit.isElytraLike(chest, maid)) {
                return;
            }
            UUID id = maid.getUUID();
            Integer start = DURA_SNAPSHOT.get(id);
            if (start == null) {
                DURA_SNAPSHOT.put(id, chest.getDamageValue());
                return;
            }
            if (chest.getDamageValue() > start) {
                chest.setDamageValue(start);
            }
        } catch (Throwable ignored) {
        }
    }

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
        DURA_SNAPSHOT.remove(id);   // 耐久快照在"不消耗耐久"选项开启时由 tick 惰性建立
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
        DURA_SNAPSHOT.remove(id);
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
            // 【实测五百八十九】飞行任务里也要认这一路：她在空袭任务、没敌人、主人跑远时，
            // TLM 的瞬移同样要拦下来改成飞过去（否则她会像用户实测那样"落地后直接传送"）。
            boolean inFlightTask = MaidFlightKit.isFlightTask(maid);
            String reason = travelBlockReason(maid, inFlightTask);
            if (reason != null) {
                logBlocked(maid, "TLM 要瞬移", reason);
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
        return canTravel(maid, false);
    }

    /**
     * @param forIdleFlightTask true = 这一路是「空袭模式附近没敌人」发起的（她自己就在空袭任务里，
     *                          不能因为"是飞行任务"就否掉；此时要求她**确实没有敌人**）
     */
    public static boolean canTravel(EntityMaid maid, boolean forIdleFlightTask) {
        return travelBlockReason(maid, forIdleFlightTask) == null;
    }

    /**
     * 她**为什么**不能赶路（null = 可以）。
     *
     * 【为什么要返回原因】"她该飞却没飞"是最难自查的一类问题（实测五百八十九：用户复现
     * 空袭打完没敌人后不跟随，日志里一行都没有）。把判定理由做成可读字符串，就能在日志里
     * 直接看到是"主人太近 / 缺鞘翅 / 没燃料 / 刚受伤 / 开关关着"里的哪一条。
     */
    public static String travelBlockReason(EntityMaid maid, boolean forIdleFlightTask) {
        if (maid == null || !maid.isAlive()) {
            return "她不在了";
        }
        try {
            if (travelBlockReasonInner(maid, forIdleFlightTask) instanceof String s) {
                return s;
            }
            return null;
        } catch (Throwable ignored) {
            return "判定异常";
        }
    }

    private static String travelBlockReasonInner(EntityMaid maid, boolean forIdleFlightTask) {
        try {
            // 总开关（实测五百九十 修：这一条曾经漏在资格判定里——行为自己会拒绝，但命令的校验口径
            //  必须与行为一致，否则"默认关"时命令还会回一句"开始赶路"，让人以为生效了）
            if (!MaidSmartConfig.MISC_ELYTRA_TRAVEL.get()) {
                return "鞘翅赶路开关关着（默认关，可选功能）";
            }
            // 空袭模式自己会飞——只有"空袭待机"那一路允许在飞行任务里赶路
            if (MaidFlightKit.isFlightTask(maid) != forIdleFlightTask) {
                return forIdleFlightTask ? "她不在空袭任务里" : "她在空袭任务里（那一类由待机跟随负责）";
            }
            if (forIdleFlightTask && !MaidSmartConfig.MISC_ELYTRA_TRAVEL_AIRRAID.get()) {
                return "空袭待机跟随开关关着";
            }
            // 停放/骑乘/睡觉/水/岩浆：不飞（水里滑翔没意义，岩浆是找死）
            if (maid.isPassenger() || maid.isMaidInSittingPose() || maid.isSleeping()) {
                return "骑乘/坐姿/睡觉中";
            }
            if (maid.isInWater() || maid.isInLava()) {
                return "在水里/岩浆里";
            }
            // 脑冻结 / 守家：不做跟随，也不赶路
            if (!maid.canBrainMoving()) {
                return "脑冻结（干活/被固定）";
            }
            if (maid.isHomeModeEnable()) {
                return "守家模式";
            }
            // 有攻击目标（挨打/追击中）：先打完再说，别背对敌人起飞
            if (hasEnemy(maid)) {
                return "她还有敌人";
            }
            // 刚被谁打过（3 秒内）：同理让战斗/自保先处理。
            // 【实测五百八十九 放宽】空袭待机那一路**不看这条**——刚打完怪（主人转和平模式，
            // 怪瞬间消失）时她多半还在"3 秒内被打过"窗口里，拿它当门槛会刚好把这一路卡死。
            if (!forIdleFlightTask && maid.getLastHurtByMob() != null
                    && maid.getLastHurtByMobTimestamp() + 60 > maid.tickCount) {
                return "刚挨过打（3 秒内）";
            }
            // 鞘翅（穿在身上/手上/背包里都算，"穿"这一步由行为负责）
            if (!MaidFlightKit.hasElytra(maid)) {
                return "没有可用鞘翅";
            }
            // 燃料：烟花（稳）或"提供高度"的位移法术（没烟花的整合包也能用）
            boolean fw = MaidSmartConfig.MISC_ELYTRA_TRAVEL_FIREWORK.get() && MaidFlightKit.hasFirework(maid);
            boolean sp = MaidSmartConfig.MISC_ELYTRA_TRAVEL_SPELL.get() && MaidFlightKit.hasClimbSpell(maid);
            if (!fw && !sp) {
                return "没有燃料（烟花/位移法术都没有）";
            }
            return null;
        } catch (Throwable ignored) {
            return "判定异常";
        }
    }

    /** 她手上有没有"要去打的敌人"（空袭待机判定与赶路豁免共用） */
    public static boolean hasEnemy(EntityMaid maid) {
        try {
            if (maid.getBrain()
                    .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                    .isPresent()) {
                return true;
            }
            return maid.getTarget() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测五百八十八：空袭模式**附近没有可抵达的敌人**时，改由鞘翅赶路跟主人飞过去。
     *
     * 由 {@code MaidFlightCombatBehavior} 在"目标为空"那一支调用；返回 true = 已经把飞行交给
     * 赶路逻辑（它随后 return，不再做 endFlightSafely 的滑降收尾）。
     *
     * 距离门槛：用 TLM 那套阈值口径（工作范围半径 + 2）与 12 格取大者——主人就在旁边时照旧落地待命。
     */
    public static boolean requestOwnerFollow(EntityMaid maid) {
        return requestOwnerFollow(maid, false);
    }

    /**
     * @param logWhy true = 拒绝时记一条诊断日志（空袭待机那条链路每 tick 都要问，需要能自查）
     */
    public static boolean requestOwnerFollow(EntityMaid maid, boolean logWhy) {
        try {
            String reason = travelBlockReason(maid, true);
            if (reason != null) {
                if (logWhy) {
                    logBlocked(maid, "空袭待机", reason);
                }
                return false;
            }
            LivingEntity owner = maid.getOwner();
            if (owner == null || !owner.isAlive() || maid.level() != owner.level()) {
                if (logWhy) {
                    logBlocked(maid, "空袭待机", owner == null ? "找不到主人" : "主人不同维度/不在");
                }
                return false;
            }
            // 门槛与 TLM 自己的瞬移阈值同口径（工作范围半径 + 2，最低 8 格）：低于它她本来就该
            // 走路跟着、高过它 TLM 才想瞬移——两边对齐才不会出现"瞬移被拦了、飞又不起飞"的死区。
            double need = Math.max(8.0, maid.getRestrictRadius() + 2.0);
            double d2 = maid.distanceToSqr(owner);
            if (d2 < need * need) {
                if (logWhy) {
                    logBlocked(maid, "空袭待机", "主人太近（" + String.format("%.1f", Math.sqrt(d2))
                            + " < " + (int) need + " 格）");
                }
                return false;
            }
            Long cd = COOLDOWN.get(maid.getUUID());
            if (cd != null && now(maid) < cd) {
                if (logWhy) {
                    logBlocked(maid, "空袭待机", "落地防抖中（还有 " + (cd - now(maid)) + " tick）");
                }
                return false;
            }
            REQUEST.put(maid.getUUID(), now(maid));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 结束会话的对外入口（战斗行为切回时用） */
    public static void endSession(EntityMaid maid, String reason) {
        end(maid, reason);
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
