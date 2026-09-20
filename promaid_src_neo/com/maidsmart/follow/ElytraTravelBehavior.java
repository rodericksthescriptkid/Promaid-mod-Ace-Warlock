package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import com.maidsmart.combat.MaidSpellCastCompat;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * v1.2.2 实测五百八十七【鞘翅赶路】——非战斗常态下，主人跑远时让她穿鞘翅飞过去。
 *
 * 【场景】整合包里玩家自己有鞘翅/喷气背包/各种跨地形手段，女仆却只有"瞬移跟随"和搭路；
 * 在空岛型维度（末地外岛、天境之类）观感尤其割裂。本行为补上"她也能滑翔着跟过来"。
 *
 * 【与空袭模式的关系】飞行动力学同源（滑翔靠视线、推进靠烟花/位移法术），但**触发逻辑完全不同**：
 * 空袭是"围着敌人打"，本行为是"跟着主人赶路"。两者互斥（她在空袭任务里时本行为不启动）。
 *
 * 【流程】
 * <ol>
 *   <li>TLM 的跟随任务在"离主人 &gt; 工作范围半径 + 2"时想瞬移 →
 *       {@code MaidTeleportPreserveMixin} 问 {@link ElytraTravel#interceptTeleport}；</li>
 *   <li>够资格（有鞘翅 + 有燃料 + 状态允许）→ 拦下瞬移、挂起"起飞请求"；</li>
 *   <li>本行为每 tick 消费请求 → 建立会话 → 起跳/开鞘翅 → 朝主人滑翔（烟花推进，没烟花用
 *       "提供高度"的位移法术续高度）→ 进到 6 格内收鞘翅、交还 TLM 跟随；</li>
 *   <li>任何一条兜底（超时/断燃料/进水进岩浆/撞地形没进展/目标丢失）→ 立刻结束会话，
 *       **让瞬移恢复正常**——宁可瞬移，绝不把她卡在半路。</li>
 * </ol>
 */
public class ElytraTravelBehavior extends Behavior<EntityMaid> {

    /** 一次烟花推进后，多久内不再放（1 枚烟花自己能烧约 2 秒） */
    private static final int FIREWORK_INTERVAL = 30;
    /** 每个会话只记一次"用哪个法术推进"的日志 */
    private static final java.util.Set<java.util.UUID> FIRST_CAST_LOGGED = new java.util.HashSet<>();
    /** 法术推进的节流表：UUID → 下一次可施法的 gameTime（实测五百八十八：没有它会每 tick 连放） */
    private static final java.util.Map<java.util.UUID, Long> SPELL_READY = new java.util.HashMap<>();
    /**
     * 没进展判定的窗口：这么久距离没缩短 1 格以上，判定为撞地形/被挡住。
     *
     * 【为什么是 200 而不是 100（实测五百八十八）】位移法术的推进节奏由它自己的冷却决定
     * （升腾 15 秒、烈焰冲锋 10 秒 = 200~300 tick）。窗口取 100 时，第二次施法还没到，
     * 她就被判定"没进展"收工了——看上去像"法术推进没生效"。
     */
    private static final int STALL_WINDOW = 200;
    /** 结会话的原因（stop 里兜底用） */
    private final ThreadLocal<String> endReason = new ThreadLocal<>();

    /** 失败兜底：会话必然有终点，避免任何"漏掉结束"的路径把她永久停飞（见 ElytraTravel 类注释） */
    private static final int HARD_TIMEOUT_FACTOR = 20;

    public ElytraTravelBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        try {
            if (!MaidSmartConfig.MISC_ELYTRA_TRAVEL.get()) {
                return false;
            }
            // 已在赶路：继续跑
            if (ElytraTravel.isTraveling(maid)) {
                return true;
            }
            // 只有被 mixin 挂上请求（= TLM 正要瞬移）时才起飞
            if (ElytraTravel.hasFreshRequest(maid)) {
                // 请求挂起后又掉了鞘翅/燃料（或进了水），请求作废。
                // 判定口径必须与"请求是怎么来的"一致：她要是在空袭任务里（待机/命令触发），
                // 就得按待机那一路判定——否则请求挂上又被自己否掉，永远不起飞（实测踩过）。
                boolean inFlightTask = MaidFlightKit.isFlightTask(maid);
                String why = ElytraTravel.travelBlockReason(maid, inFlightTask);
                if (why != null) {
                    ElytraTravel.logBlocked(maid, "请求已挂起", why);
                    return false;
                }
                return true;
            }
            // 【实测五百八十九 空袭待机跟随：每 tick 自检，不再依赖战斗行为那一 tick 的接管】
            // 旧版只在"敌人刚消失的那一 tick"由 MaidFlightCombatBehavior 挂请求——那一刻主人多半
            // 还在旁边（距离不够），于是没接管成功；战斗行为随后因为没有目标整个停掉，之后再也没人
            // 尝试 → 用户实测到的"空袭打完，她原地着陆后直接传送"。现在这里每 tick 自检：
            // 只要她在空袭任务、没敌人、主人跑远了，就自己起飞跟过去（与她切到空闲时同一条路径）。
            // 拒绝的原因会写进日志（[鞘翅赶路·待机] … 没起飞：空袭待机 → <原因>），便于自查。
            return ElytraTravel.requestOwnerFollow(maid, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        try {
            ElytraTravel.begin(maid);
            // 穿鞘翅（滑翔只认胸甲槽）。装备口径与空袭一致：优先带护甲的那种"鞘翅胸甲"。
            if (MaidSmartConfig.MISC_ELYTRA_TRAVEL_AUTO_EQUIP.get()) {
                MaidFlightKit.equip(maid);
            }
            this.endReason.set("未知");
            takeOff(maid);
            PromaidLog.log("鞘翅赶路", PromaidLog.nameOf(maid) + " 起飞赶路（"
                    + (ElytraTravel.debugTarget(maid) != null ? "前往指定坐标" : "去追主人") + "）");
        } catch (Throwable t) {
            ElytraTravel.end(maid, "起飞异常：" + t);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return ElytraTravel.isTraveling(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        try {
            if (!ElytraTravel.isTraveling(maid)) {
                return;
            }
            // ── 兜底一：时间上限 ──
            int maxSeconds = Math.max(5, MaidSmartConfig.MISC_ELYTRA_TRAVEL_MAX_SECONDS.get());
            if (gameTime - ElytraTravel.sessionStart(maid) > maxSeconds * HARD_TIMEOUT_FACTOR) {
                bail(maid, "超时（" + maxSeconds + " 秒）");
                return;
            }
            // ── 兜底二：目标没了（主人去世/换维度；调试目标不受影响） ──
            Vec3 aim = ElytraTravel.travelTarget(maid);
            if (aim == null) {
                bail(maid, "目标丢失");
                return;
            }
            // ── 兜底三：水里/岩浆里（滑翔没意义，岩浆是找死） ──
            if (maid.isInWater() || maid.isInLava()) {
                bail(maid, "落水/岩浆");
                return;
            }
            double dist = maid.position().distanceTo(aim);
            if (dist <= ElytraTravel.LAND_DISTANCE) {
                bail(maid, "已到主人身边");
                return;
            }
            // ── 兜底三·五：出现敌人 —— 立刻把飞行交还空袭（别背对敌人赶路） ──
            if (ElytraTravel.hasEnemy(maid)) {
                bail(maid, "出现敌人，切回战斗");
                return;
            }
            // ── 兜底四：燃料耗尽（烟花 + 位移法术都没了）→ 滑翔降落，交给跟随/瞬移收尾 ──
            boolean canFirework = MaidSmartConfig.MISC_ELYTRA_TRAVEL_FIREWORK.get()
                    && MaidFlightKit.hasFirework(maid);
            boolean canSpell = MaidSmartConfig.MISC_ELYTRA_TRAVEL_SPELL.get()
                    && MaidFlightKit.hasClimbSpell(maid);
            if (!canFirework && !canSpell) {
                bail(maid, "燃料耗尽");
                return;
            }
            // ── 兜底五：没进展（撞地形/被墙挡住/主人在屋里） ──
            if (ElytraTravel.stalled(maid, dist, STALL_WINDOW)) {
                bail(maid, "没进展（撞地形？）");
                return;
            }
            // ── 正常操纵 ──
            // 独占移动：清掉走路目标 + 停导航（TLM 跟随每 tick 都会写、空闲散步也会写，
            // 不挡住的话 MaidMoveControl 会把我们的起跳动量/朝向一起覆盖掉）
            takeoverMovement(maid);
            steer(maid, aim);
            // 落地了（撞地/滑翔断了）：再跳一次继续赶路，由"没进展"兜底收尾
            if (maid.onGround()) {
                takeOff(maid);
            }
            // 必须每 tick 置位：滑翔的操纵杆就是视线，掉出滑翔态她就成自由落体了
            MaidFlightKit.setGliding(maid, true);
            // 推进：烟花优先（一次约 2 秒推力），不在冷却里就用"提供高度"的位移法术续高度。
            // 站在地上时**必须先给持续推力**才起得来（见 takeOff 注释），所以地面优先点烟花。
            thrust(level, maid, gameTime, canFirework, canSpell, maid.onGround());
        } catch (Throwable t) {
            bail(maid, "运行时异常：" + t);
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        try {
            MaidFlightKit.setGliding(maid, false);
            MaidSpellCastCompat.clearCastTarget(maid);
            String reason = this.endReason.get();
            if (reason == null) {
                reason = "跟随恢复";
            }
            this.endReason.remove();
            if (ElytraTravel.debugTarget(maid) != null) {
                ElytraTravel.clearDebugTarget(maid);
            }
            ElytraTravel.end(maid, reason);
        } catch (Throwable ignored) {
        }
    }

    /** 收工：设原因并**立刻结束会话**（会话一没，canStillUse 就变 false，大脑随即停掉本行为）。
     *  注意：只设原因、不结束会话是个坑——行为会一直 tick、瞬移也一直被拦着（实测踩过）。 */
    private void bail(EntityMaid maid, String reason) {
        this.endReason.set(reason);
        try {
            MaidFlightKit.setGliding(maid, false);
        } catch (Throwable ignored) {
        }
        ElytraTravel.end(maid, reason);
    }

    /* ---------------- 操纵 ---------------- */

    /**
     * 开鞘翅 + 起跳。
     *
     * 【为什么单靠跳跃起不来（实测）】跳跃只在当 tick 写一次动量，而 {@code MaidMoveControl.tick()}
     * 在她大脑之后运行、会把动量/朝向重写一遍——落在地上时她于是"跳一下就落回来"，
     * 永远进不了滑翔。真正能把地面女仆顶起来的是**持续推力**：烟花（约 2 秒推力）或
     * 位移法术（升腾附带的悬浮效果）。所以这里起跳只是辅助，起飞推力由 tick 里的
     * {@link #thrust} 负责，并且每 tick 重新置位滑翔标志（落地会被原版清掉）。
     */
    private void takeOff(EntityMaid maid) {
        try {
            if (maid.onGround()) {
                Vec3 v = maid.getDeltaMovement();
                maid.setDeltaMovement(v.x, 0.42, v.z);
                maid.hasImpulse = true;
            }
            MaidFlightKit.setGliding(maid, true);
        } catch (Throwable ignored) {
        }
    }

    /** 独占移动：清走路目标 + 停导航（工作/跟随/散步写进来的目标一起作废） */
    private void takeoverMovement(EntityMaid maid) {
        try {
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET);
            maid.getNavigationManager().resetNavigation();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把身体转向瞄准点：滑翔的推力方向就是视线方向（原版 {@code LivingEntity.travel} 的滑翔分支），
     * 所以"操纵"= 摆朝向，不做人工转弯。
     *
     * 俯仰按高差自动配平：要爬就抬（最多 30°），要掉就压（最多 45°）——抬太多会失速、压太多会砸地。
     */
    private void steer(EntityMaid maid, Vec3 aim) {
        try {
            double dx = aim.x - maid.getX();
            double dz = aim.z - maid.getZ();
            double dh = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
            double dy = aim.y - (maid.getY() + maid.getBbHeight() * 0.5);
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            float pitch = (float) (-(Mth.atan2(dy, dh) * (180.0 / Math.PI)));
            pitch = Mth.clamp(pitch, -30.0f, 45.0f);
            maid.setYRot(yaw);
            maid.setXRot(pitch);
            maid.yRotO = yaw;
            maid.xRotO = pitch;
            maid.setYHeadRot(yaw);
            maid.setYBodyRot(yaw);
            try {
                maid.getLookControl().setLookAt(aim.x, aim.y, aim.z, 360.0f, 360.0f);
            } catch (Throwable ignored) {
            }
            // 【必须同时交给 LookControl（实测五百二十九的同一个坑）】Mob.serverAiStep 先跑大脑、
            // 之后才跑 LookControl.tick()，它第一件事就是 if (resetXRotOnTick()) mob.setXRot(0)
            // ——女仆恒真，光写 applyRotation 的话抬头角**同 tick 就被归零**，烟花的推力于是全给了
            // 水平方向（实测：速度 1.3 格/tick、vy ≈ 0，怎么放烟花都爬不起来）。这里把"期望点"
            // 一并交给它，LookControl 据此反解出的俯仰与我们写进去的一致。
        } catch (Throwable ignored) {
        }
    }

    /** 推进：烟花（持续推力，优先）→ 位移法术（续高度） */
    private void thrust(ServerLevel level, EntityMaid maid, long gameTime,
                        boolean canFirework, boolean canSpell, boolean grounded) {
        if (canFirework && ElytraTravel.fireworkReady(maid)) {
            if (launchFirework(level, maid)) {
                return;
            }
        }
        if (canSpell && !grounded) {
            spellThrust(maid, gameTime);
        } else if (canSpell && !canFirework) {
            // 没烟花可放（或已放空）时，站在地上也要靠法术顶起来
            spellThrust(maid, gameTime);
        }
    }

    /**
     * 放一枚"挂载型"烟花推进（无爆炸星，避免到期爆炸伤到只有 20 血的她）——与空袭同源。
     *
     * 消耗的仍是玩家给她装的真烟花（1 枚）。
     */
    private boolean launchFirework(ServerLevel level, EntityMaid maid) {
        try {
            ItemStack consumed = MaidFlightKit.takeFirework(maid);
            if (consumed.isEmpty()) {
                return false;
            }
            ItemStack rocketStack = new ItemStack(Items.FIREWORK_ROCKET);
            rocketStack.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
            level.addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                    level, rocketStack, maid));
            ElytraTravel.markFirework(maid, FIREWORK_INTERVAL);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 用"提供高度"的位移法术续一口气（没有烟花/烟花在冷却时）。
     *
     * 口径与空袭的起飞/补高一致：**不看法术自身冷却**（见空袭那份注释——位移手段一向让女仆
     * 比玩家宽松，激流三叉戟就是忽略原版限制的先例），冷却只按空袭位移间隔写回。
     * 朝向不用改：滑翔中她本来就抬着头朝目标，法术的冲量方向正好等于她的视线。
     */
    private boolean spellThrust(EntityMaid maid, long gameTime) {
        try {
            long ready = SPELL_READY.getOrDefault(maid.getUUID(), 0L);
            if (gameTime < ready) {
                return false;
            }
            String[] ids = MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB_SPELLS.get().toArray(new String[0]);
            MaidSpellCastCompat.warnUnknownSpellIds(ids);   // id 写错时报一次（否则表现只是"第一个法术永不生效"）
            String spell = MaidSpellCastCompat.findClimbSpellIgnoringCooldown(maid, ids);
            if (spell == null) {
                return false;
            }
            int lvl = MaidSpellCastCompat.spellLevelInBooks(maid, spell);
            if (lvl <= 0) {
                lvl = MaidSpellCastCompat.DASH_SPELL_FALLBACK_LEVEL;
            }
            // 【节流（实测五百八十八 修）】原来这里只把冷却"写回"给她、挑法术时又刻意不看冷却，
            // 于是每 tick 都能中一记：升腾（纯 Y 轴冲量）几秒就把她顶到云上，烈焰冲锋则让她
            // 一直摆站着施法的动画、盖掉作者给鞘翅滑翔指定的游泳姿势。现在按"最小间隔 + （默认）
            // 法术自身冷却"取大者节流；间隔取不到法术自身冷却时，用空袭位移间隔兜底。
            int interval = 0;
            try {
                interval = Math.max(10, MaidSmartConfig.MISC_ELYTRA_TRAVEL_SPELL_INTERVAL.get());
                if (MaidSmartConfig.MISC_ELYTRA_TRAVEL_SPELL_RESPECT_COOLDOWN.get()) {
                    interval = Math.max(interval, MaidSpellCastCompat.spellCooldownTicks(spell));
                }
            } catch (Throwable ignored) {
            }
            if (interval <= 0) {
                interval = Math.max(20, MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL.get());
            }
            MaidSpellCastCompat.clearCastTarget(maid);
            if (!MaidSpellCastCompat.castSpecific(maid, spell, lvl, interval)) {
                return false;
            }
            SPELL_READY.put(maid.getUUID(), gameTime + interval);
            if (!FIRST_CAST_LOGGED.contains(maid.getUUID())) {
                FIRST_CAST_LOGGED.add(maid.getUUID());
                PromaidLog.log("鞘翅赶路", PromaidLog.nameOf(maid) + " 用位移法术推进：" + spell
                        + " Lv" + lvl + "（每 " + interval + " tick 一记；法术表里排在前面的优先）");
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
