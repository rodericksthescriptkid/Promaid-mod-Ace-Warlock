package com.maidsmart.bd;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-6【超越维度·存入】——采矿 / 伐木 / 收割的产物回收到**主人的主网络**。
 *
 * <h2>为什么先做存入、不做取出（需求方的直觉是对的）</h2>
 * <b>存入</b>不需要"知道要什么"：物品已经在她背包里，我们手里就有精确的 {@code ItemStack}，
 * 直接构 key 插进去即可。<b>取出</b>要先回答"取什么"——按物品 key 查是 O(1)，但"任意物品"
 * 要么列举网络内容（O(n)）、要么用 {@code fuzzy=true}（源码自己写了那是**线性扫描**），
 * 所以取出要配一套"向网络要什么"的表达方式，那是下一步的事。
 *
 * <h2>安全口径（宁可少搬，不可搬错）</h2>
 * <ol>
 *   <li><b>只扫背包</b>（{@code getAvailableBackpackInv}），**双手一律不碰**——手里的剑不会被搬走；</li>
 *   <li><b>只搬白名单产物</b>：{@code c:logs} / {@code c:ores} / {@code c:raw_materials} /
 *       {@code c:gems} / {@code c:crops}；</li>
 *   <li><b>硬排除</b>：有耐久的（工具/护甲）、附魔的、改过名的、TLM 自己的物品（{@code touhou_little_maid}）、
 *       以及种子/树苗/食物/桶/火把——这些她干活要用；</li>
 *   <li><b>不丢东西</b>：先 <i>模拟取出</i> 确认数量 → <i>真插入</i>网络（拿回剩余量）→ 才从背包
 *       <i>真扣除</i>。顺序刻意如此：任何一步失败都不会出现"背包扣了、网络没进"的丢件；</li>
 *   <li>每笔都写日志（物品 id × 数量），可在 {@code promaid.log} 里对账。</li>
 * </ol>
 *
 * <h2>开关</h2>
 * 默认关；per-maid 开关存 {@code persistentData}（随魂符/存档走）。
 * {@code /maid_smart bd_deposit_dry} 可以"只看不搬"，先确认名单再开。
 */
public final class MaidBdDeposit {

    /** persistentData 键。 */
    private static final String TAG = "maid_smart_bd_deposit";

    /** 扫描间隔（tick）：1 秒一次。 */
    private static final int SWEEP_EVERY = 20;
    /** 每次最多搬几组 / 几个。 */
    private static final int MAX_STACKS_PER_SWEEP = 8;
    private static final long MAX_ITEMS_PER_SWEEP = 4096L;

    /**
     * 白名单：只搬这些（标签）。
     *
     * <p>【实测 G-6 修正】只写公共标签 {@code c:logs} 会**漏掉原版原木**——公共标签由各模组自己
     * 生成，原版物品不一定被收进去（无头实测：oak_log 没被匹配到）。所以这里**两套都列**：
     * 公共命名空间 {@code c:*} 与**原版自己的** {@code minecraft:*}_ores / {@code minecraft:logs}。
     * 不在任何标签里的（例如某些模组的自定义作物）走下面的 {@link #GOOD_IDS} 明细名单。
     */
    private static TagKey<Item> tag(String ns, String path) {
        return net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static final TagKey<Item>[] GOOD_TAGS = new TagKey[]{
            tag("c", "logs"), tag("minecraft", "logs"),
            tag("c", "ores"), tag("minecraft", "ores"),
            tag("minecraft", "coal_ores"), tag("minecraft", "iron_ores"), tag("minecraft", "copper_ores"),
            tag("minecraft", "gold_ores"), tag("minecraft", "redstone_ores"), tag("minecraft", "lapis_ores"),
            tag("minecraft", "diamond_ores"), tag("minecraft", "emerald_ores"), tag("minecraft", "quartz_ores"),
            tag("c", "raw_materials"), tag("c", "gems"), tag("c", "dusts"), tag("c", "crops"),
    };

    /** 白名单补充：不在标签里的常见收成（原版作物大多没有统一标签）。 */
    private static final String[] GOOD_IDS = {
            // 挖矿产物里**不在任何统一标签**里的那些（无头实测：煤就漏了）
            "minecraft:coal", "minecraft:flint", "minecraft:clay_ball", "minecraft:quartz",
            "minecraft:amethyst_shard", "minecraft:glowstone_dust", "minecraft:pointed_dripstone",
            "minecraft:obsidian", "minecraft:ancient_debris", "minecraft:netherite_scrap",
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
            "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:melon_slice", "minecraft:pumpkin",
            "minecraft:nether_wart", "minecraft:sweet_berries", "minecraft:glow_berries",
            "minecraft:cocoa_beans", "minecraft:kelp", "minecraft:cactus", "minecraft:hay_block",
            "minecraft:string", "minecraft:leather", "minecraft:feather", "minecraft:bone",
            "minecraft:gunpowder", "minecraft:slime_ball", "minecraft:ender_pearl",
    };

    /** 黑名单：干活要用 / 不该动（标签）。 */
    private static final TagKey<Item>[] KEEP_TAGS = new TagKey[]{
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "seeds")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "saplings")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "foods")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "buckets")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools")),
            net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "armors")),
    };

    /** 黑名单：这些模组自己的东西一律不动（TLM 的魂符/背包等她自己的物件）。 */
    private static final String[] KEEP_MODIDS = {"touhou_little_maid", "promaid"};

    private static final Map<UUID, Integer> COUNTER = new HashMap<>();
    private static boolean hooked;

    private MaidBdDeposit() {
    }

    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidBdDeposit());
    }

    public static boolean isOn(EntityMaid maid) {
        try {
            return maid != null && ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().getBoolean(TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setOn(EntityMaid maid, boolean on) {
        try {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid)
                    .getPersistentData().putBoolean(TAG, on);
        } catch (Throwable ignored) {
        }
        PromaidLog.log("超越维度存入", (maid == null ? "?" : maid.getName().getString())
                + " 产出回收 = " + on);
    }

    /** 这一堆该不该搬：白名单命中、且没踩任何一条硬排除。 */
    public static boolean isProduct(ItemStack s) {
        return !s.isEmpty() && verdict(s) == null;
    }

    /**
     * dry-run：把**每一格**的判定结果与理由都列出来（不只列命中的）——
     * 调名单时必须能看见"为什么没搬这一格"，否则只能猜。
     */
    public static List<String> preview(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        IItemHandler inv = backpack(maid);
        if (inv == null) {
            return out;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            String id = com.maidsmart.goety.MaidGoetyCompat.itemId(s);
            String why = verdict(s);
            out.add("槽" + i + " " + id + " × " + s.getCount() + " → " + (why == null ? "会搬" : "保留（" + why + "）"));
        }
        return out;
    }

    /** 判定这一堆该不该搬；返回 null = 该搬，否则返回**保留的理由**。 */
    public static String verdict(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "空";
        }
        try {
            if (s.isDamageableItem()) {
                return "有耐久（工具/护甲）";
            }
            if (s.isEnchanted()) {
                return "附魔";
            }
            if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
                return "改过名";
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id != null) {
                for (String m : KEEP_MODIDS) {
                    if (m.equals(id.getNamespace())) {
                        return "模组自有物品（" + m + "）";
                    }
                }
                if (id.getPath().equals("torch") || id.getPath().equals("lantern")) {
                    return "照明要用的";
                }
            }
            for (TagKey<Item> t : KEEP_TAGS) {
                if (s.is(t)) {
                    return "保留标签 " + t.location();
                }
            }
            for (TagKey<Item> t : GOOD_TAGS) {
                if (s.is(t)) {
                    return null;
                }
            }
            if (id != null) {
                String full = id.toString();
                for (String good : GOOD_IDS) {
                    if (good.equals(full)) {
                        return null;
                    }
                }
            }
            return "未命中白名单";
        } catch (Throwable t) {
            return "判定异常";
        }
    }

    private static IItemHandler backpack(EntityMaid maid) {
        try {
            return maid == null ? null : maid.getAvailableBackpackInv();
        } catch (Throwable t) {
            return null;
        }
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide() || !isOn(maid)) {
            return;
        }
        int n = COUNTER.merge(maid.getUUID(), 1, Integer::sum);
        if (n % SWEEP_EVERY != 0) {
            return;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.available() || !MaidBdCompat.hasAnyNet(owner)) {
            return;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        if (net == null) {
            return;
        }
        sweep(maid, net, false);
    }

    /**
     * 执行一次回收。
     *
     * @param dryRun true 只看不搬（返回"会搬什么"的清单，不改变任何东西）
     * @return 本次实际搬入 / 会被搬入的条目（"物品 id × 数量 → 结果"）
     */
    public static List<String> sweep(EntityMaid maid, Object net, boolean dryRun) {
        List<String> out = new ArrayList<>();
        IItemHandler inv = backpack(maid);
        if (inv == null || net == null) {
            return out;
        }
        int stacks = 0;
        long movedItems = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (stacks >= MAX_STACKS_PER_SWEEP || movedItems >= MAX_ITEMS_PER_SWEEP) {
                break;
            }
            ItemStack s = inv.getStackInSlot(i);
            if (!isProduct(s)) {
                continue;
            }
            String id = com.maidsmart.goety.MaidGoetyCompat.itemId(s);
            if (dryRun) {
                out.add(id + " × " + s.getCount() + "（会不会搬：会）");
                stacks++;
                movedItems += s.getCount();
                continue;
            }
            // ① 模拟取出：确认背包这一格真的能拿出这么多（改完还得放回去，所以用 simulate）
            ItemStack probe = inv.extractItem(i, s.getCount(), true);
            if (probe.isEmpty()) {
                continue;
            }
            long want = probe.getCount();
            // ② 真插入网络：返回的是**剩余量**，插进去的就是 want - remainder
            long remainder = MaidBdCompat.insert(net, s, want);
            if (remainder < 0) {
                out.add(id + " × " + want + "（失败：插入调用异常）");
                continue;
            }
            long accepted = want - remainder;
            if (accepted <= 0) {
                out.add(id + " × " + want + "（网络拒收）");
                continue;
            }
            // ③ 网络已收下，才从背包真扣除
            ItemStack removed = inv.extractItem(i, (int) accepted, false);
            long really = removed.getCount();
            if (really < accepted) {
                // 理论上不会发生（上一步刚模拟成功、同 tick 无并发）；真发生就把多出来的还给网络
                long back = accepted - really;
                MaidBdCompat.extract(net, s, back);
                out.add(id + " × " + accepted + "（异常：只扣掉 " + really + "，已退回 " + back + "）");
                continue;
            }
            out.add(id + " × " + accepted + " → 已存入");
            stacks++;
            movedItems += accepted;
        }
        if (!dryRun && !out.isEmpty()) {
            PromaidLog.log("超越维度存入", maid.getName().getString() + " 回收 " + out.size() + " 项："
                    + String.join("；", out));
        }
        return out;
    }
}
