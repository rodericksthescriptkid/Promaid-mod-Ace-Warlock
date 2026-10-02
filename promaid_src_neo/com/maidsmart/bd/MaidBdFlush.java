package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.ContainerRef;
import com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.MaidContainerCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-12【超越维度·缓存冲刷】——把她"额外容器"（精妙背包＝缓存）里的产物批量推进数据库。
 *
 * <h2>这套三层结构是怎样长出来的</h2>
 * 需求方的设计直觉是"精妙背包＝缓存、超越维度＝数据库"，而上游 v1.2.4 已经把缓存那半做完了：
 * <pre>
 *   ①她自己背包  →  ②额外容器（精妙背包，走 TLM 的 MaidContainerCache / ContainerRef）→  ③超越维度
 * </pre>
 * 之前 ③ 只在两个时机进料：她背包里的产物按规则回收、以及她背包与缓存都塞不下时的溢出。
 * **缓存里积压的那部分一直没人管**——本类就是补这一段：定期把缓存里的产物倒进数据库。
 *
 * <h2>为什么直接用 ContainerRef.extract，而不是借上游的 pull</h2>
 * 上游的 {@code MaidExtraContainer.pull} 是"请 TLM 把东西搬进**她自己的背包**"——那是给
 * 她干活用的；我们要的是"搬进数据库"，绕一圈她的背包只会白折腾（还可能因为背包满而卡住）。
 * {@code ContainerRef#extract(maid, 判据, 上限)} 能**直接从容器里取出来**，而且 TLM 自家的
 * {@code SBackpackSlotRef} 确实实现了它（javap 实证），所以这条路是干净的。
 *
 * <h2>安全规矩</h2>
 * <ol>
 *   <li>取出后立刻插库；插不进去（或库里塞不下）就**原样放回容器**——顺序保证不丢件；</li>
 *   <li>判据沿用 {@link MaidBdDeposit#isProduct}：缓存里的**非产物**（她干活要用的）一律不动；</li>
 *   <li>每轮限量（默认 8 组 / 4096 个），避免一次卡顿；每笔都写日志。</li>
 * </ol>
 *
 * <p>【预演的代价】TLM 的容器 API 只提供"按判据取出"，没有纯只读版本，所以
 * {@code bd_flush_dry} 是"取出来再放回去"，并把这个事实写进日志——如果连放回都失败，
 * 就直接推进库里（宁可入错地方，也不丢件）。
 */
public final class MaidBdFlush {

    /** 与回收/补货同一只开关，但节奏慢一些：2 秒一轮（搬运是批量的，不必每秒跑）。 */
    private static final int CHECK_EVERY = 40;
    private static final int MAX_STACKS = 8;
    private static final long MAX_ITEMS = 4096L;

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();

    /**
     * 【G-14】每只女仆"最近一次自动冲刷"的备忘。
     *
     * <p>为什么需要：自动冲刷每 2 秒跑一次，而玩家装备背包、切窗口、敲命令至少要几秒 ⇒
     * 等玩家敲下 {@code bd_flush_dry} 时缓存早就空了，于是永远看到"没有可冲刷的产物"，
     * 只能靠网络数量前后对比去猜（需求方实测正是如此：他发现装备后网络从 24 变 49）。
     */
    private record LastFlush(long tick, List<String> lines) {
    }

    private static final Map<UUID, LastFlush> LAST = new HashMap<>();
    private static boolean hooked;

    private MaidBdFlush() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidBdFlush());
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide() || !MaidBdDeposit.isOn(maid)) {
            return;
        }
        if (!hasExtraContainers(maid)) {
            return;   // 没有额外容器（没装精妙背包/旅行者背包，或没插在饰品栏）→ 零开销退出
        }
        int n = COUNTER.merge(maid.getUUID(), 1, Integer::sum);
        if (n % CHECK_EVERY != 0) {
            return;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        if (net != null) {
            flush(maid, net, false);
        }
    }

    private static boolean hasExtraContainers(EntityMaid maid) {
        try {
            List<ContainerRef> refs = MaidContainerCache.getContainers(maid);
            return refs != null && refs.size() > 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 只看：报告额外容器里有哪些产物（实现方式是"取出来再放回去"，日志会写明）。
     *
     * <p>如果缓存此刻是空的，就把"最近一次自动冲刷搬走了什么"一并报出来——否则玩家只会
     * 看到一片空白，无法确认它到底工作过没有。
     */
    public static List<String> preview(EntityMaid maid) {
        List<String> out = flush(maid, null, true);
        if (out.isEmpty() && maid != null) {
            LastFlush last = LAST.get(maid.getUUID());
            if (last != null) {
                out.add("（缓存此刻是空的；最近一次自动冲刷搬走了：" + String.join("；", last.lines()) + "）");
            }
        }
        return out;
    }

    /**
     * 冲刷一次。
     *
     * @param net    目标网络（dryRun 时可传 null）
     * @param dryRun true = 只看（取出来再放回去）
     */
    public static List<String> flush(EntityMaid maid, Object net, boolean dryRun) {
        List<String> out = new ArrayList<>();
        List<ContainerRef> refs;
        try {
            refs = MaidContainerCache.getContainers(maid);
        } catch (Throwable t) {
            return out;
        }
        if (refs == null || refs.size() <= 1) {
            return out;   // 第 0 项是她自己
        }
        int stacks = 0;
        long items = 0;
        for (int i = 1; i < refs.size(); i++) {
            ContainerRef ref = refs.get(i);
            if (ref == null) {
                continue;
            }
            while (stacks < MAX_STACKS && items < MAX_ITEMS) {
                int want = (int) Math.min(64L, MAX_ITEMS - items);
                ItemStack got;
                try {
                    got = ref.extract(maid, MaidBdDeposit::isProduct, want);
                } catch (Throwable t) {
                    break;   // 这个容器不支持取（例如某些实现只做插入）→ 跳过
                }
                if (got == null || got.isEmpty()) {
                    break;
                }
                String id = com.maidsmart.goety.MaidGoetyCompat.itemId(got);
                if (dryRun) {
                    ItemStack back = ref.insert(maid, got, false);
                    boolean ok = back == null || back.isEmpty();
                    out.add(id + " × " + got.getCount() + " → 会被冲刷（" + MaidBdDeposit.moveVia(got)
                            + (ok ? "" : "；注意：放回失败 " + back.getCount() + " 个") + "）");
                    stacks++;
                    items += got.getCount();
                    continue;
                }
                // 【G-13 审计】记下"网络里 X → Y"，事后可对账
                long beforeInNet = MaidBdCompat.countOf(net, got);
                long remainder = MaidBdCompat.insert(net, got, got.getCount());
                if (remainder < 0 || remainder == got.getCount()) {
                    ItemStack back = ref.insert(maid, got, false);
                    out.add(id + " × " + got.getCount() + "（网络没收下，已放回"
                            + (back != null && !back.isEmpty() ? "但只放回 " + (got.getCount() - back.getCount()) + " 个" : "") + "）");
                    break;
                }
                long accepted = got.getCount() - Math.max(0, remainder);
                if (remainder > 0) {
                    ItemStack rest = got.copy();
                    rest.setCount((int) Math.max(0, remainder));
                    ref.insert(maid, rest, false);
                }
                long afterInNet = MaidBdCompat.countOf(net, got);
                out.add(id + " × " + accepted + " → 从缓存存入（" + MaidBdDeposit.moveVia(got)
                        + "；网络 " + beforeInNet + " → " + afterInNet + "）");
                stacks++;
                items += accepted;
            }
        }
        if (!dryRun && !out.isEmpty()) {
            PromaidLog.log("超越维度冲刷", maid.getName().getString() + " 本轮：" + String.join("；", out));
            LAST.put(maid.getUUID(), new LastFlush(maid.level().getGameTime(), new ArrayList<>(out)));
            if (LAST.size() > 64) {
                long now = maid.level().getGameTime();
                LAST.entrySet().removeIf(e -> now - e.getValue().tick() > 6000L);   // 5 分钟前的清掉
            }
        }
        return out;
    }
}
