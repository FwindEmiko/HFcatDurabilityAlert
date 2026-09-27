package io.github.fcestial.hfcatdurabilityalert;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import net.kyori.adventure.text.TextReplacementConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 耐久事件监听器
 *
 * 核心设计（对照 DESIGN.md）：
 * 1. 主监听 PlayerItemDamageEvent — 精确到单次耐久扣减，无需轮询
 * 2. 辅助监听 PlayerItemBreakEvent — 物品损坏时发送最终警告
 * 3. EntityDamageEvent 兜底 — 部分 Paper 版本盔甲耐久扣减不触发 PlayerItemDamageEvent
 *
 * 耐久读取铁则（1.20.5+ 组件系统）：
 * - 使用 Damageable.getDamage() 获取「已损失」耐久
 * - 使用 Damageable.getMaxDamage() 获取组件 max_damage（需先 hasMaxDamage()，兼容 Stellarity 等自定义值）
 * - 剩余 = maxDamage - damage；百分比 = 剩余 / max * 100
 * - 绝不使用 item.getDurability()（已弃用），不使用 type.getMaxDurability() 作为唯一来源
 *
 * 触发时序（API 审查确认）：
 * - PlayerItemDamageEvent 在 MONITOR 阶段损伤尚未应用，因此主路径用 event.getDamage()
 *   预测「本次扣减后」的耐久再判定阈值，避免阈值跨越延迟一次事件
 * - EntityDamageEvent 兜底拿不到精确扣减量，保持现状（读扣减前状态）
 *
 * 防重复（DESIGN 第四节）：
 * - PDC 在「玩家身上真实的物品」上记录 last_warn_threshold
 *   （PlayerItemDamageEvent 携带的 ItemStack 与实物共享 NMS handle，但统一写实物更可靠）
 * - cooldown=-1：每个阈值只警告一次（降序遍历阈值，已警告的档位 continue 到更低档，
 *   命中未警告档位后警告并结束本次事件）
 * - cooldown>0：同一阈值按冷却时间可重复警告；每个阈值单独记录最近警告时间戳（PDC STRING）
 * - 物品被修复（当前百分比回升到上次警告阈值之上）时清除 PDC 标记，允许重新触发（DESIGN 4.2 方案A）
 * - 同一玩家同一槽位在 45ms 窗口内只发一条警告（EntityDamageEvent 兜底与 PlayerItemDamageEvent
 *   同 tick 连续触发时，防止一次损伤跨两个阈值连发两条）
 *
 * 线程约定：Paper/Leaf/Purpur 等非 Folia 服务器上事件处理器都在主线程执行。
 * 配置读取集中在不可变快照 {@link Settings}（volatile 发布，reload 时整体替换），
 * 去重表使用 ConcurrentHashMap + System.nanoTime() 窗口（不依赖 Bukkit.getCurrentTick()），
 * 因此不存在「reload 后读到半更新配置」的可见性问题，也不会因缺少全局 tick 而抛异常。
 * 注意：EntityDamageEvent 在 Folia 上可能由其他区域线程触发，此时读玩家背包是不安全的，
 * plugin.yml 因此声明 folia-supported: true（兜底路径的区域守卫见 ownsEntity；兼容矩阵见 README）。
 *
 * 消息输出（Paper 1.20.5 ~ 26.2 全部可用）：
 * - & 颜色代码 → translateColorCodes（兼容 &#RRGGBB）→ LegacyComponentSerializer
 *   （显式启用 hexColors + useUnusualXRepeatedCharacterHexFormat，不依赖运行时 Provider 注入）
 * - sendMessage / sendActionBar / showTitle（Adventure，Paper 全版本可用）
 * - 声音 → SoundResolver（自动适配 1.20.5~1.21.3 的枚举与 1.21.4+ 的 Registry.SOUND_EVENT）
 */
@SuppressWarnings("SpellCheckingInspection")
public class DurabilityListener implements Listener {

    /** 兜底检查的盔甲槽位 */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** legacy § 段序列化器（显式 hex 支持，兼容 §xRRGGBB 十六进制） */
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Title 时长：与旧 sendTitle(String,String,int,int,int) 的 10/40/10 tick 等价（20TPS × 50ms/tick） */
    private static final Duration TITLE_FADE_IN = Duration.ofMillis(500L);
    private static final Duration TITLE_STAY = Duration.ofMillis(2000L);
    private static final Duration TITLE_FADE_OUT = Duration.ofMillis(500L);

    /** 声音参数钳制范围（防 NaN/Infinity/异常值传给 playSound） */
    private static final float SOUND_VOLUME_MIN = 0.0f;
    private static final float SOUND_VOLUME_MAX = 2.0f;
    private static final float SOUND_PITCH_MIN = 0.5f;
    private static final float SOUND_PITCH_MAX = 2.0f;

    /** 阈值消息缺失时的通用回退文案 */
    private static final String DEFAULT_MESSAGE = "&c⚠ {item} 耐久度 {percent}%！ ({durability}/{max})";

    /** 同 tick 去重窗口（纳秒）：同一玩家同一槽位在该窗口内只发一条警告 */
    private static final long DEDUP_WINDOW_NANOS = 45_000_000L;

    private final HFcatDurabilityAlert plugin;
    private final NamespacedKey lastWarnKey;
    private final NamespacedKey lastWarnTimesKey;

    /** 同 tick 去重：playerUuid:slotName → 上次警告时刻的 nanoTime */
    private final Map<String, Long> lastWarnNanos = new ConcurrentHashMap<>();

    /** 配置快照（不可变，volatile 发布；reload 时整体替换，事件链只读） */
    private volatile Settings settings;

    public DurabilityListener(HFcatDurabilityAlert plugin) {
        this.plugin = plugin;
        this.lastWarnKey = new NamespacedKey("hfcatdurabilityalert", "last_warn_threshold");
        this.lastWarnTimesKey = new NamespacedKey("hfcatdurabilityalert", "last_warn_times");
        refresh();
    }

    /** 重建配置快照（onEnable 与 reload 时调用） */
    public void refresh() {
        this.settings = Settings.from(plugin.getConfig());
    }

    /**
     * 主事件：物品受到耐久损伤时触发。
     * PlayerItemDamageEvent 没有 getSlot()，通过匹配玩家身上的实时物品定位槽位。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(@NotNull PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(HFcatDurabilityAlert.PERMISSION_USE)) return;

        ItemStack item = event.getItem();

        // 跳过堆叠物品
        if (item.getAmount() > 1) return;

        Settings cfg = settings;
        EquipmentSlot slot = findSlot(player, item, cfg);
        if (slot == null) return; // 不在监控范围内

        // 主路径：用 event.getDamage() 预测扣减后的耐久再判定（MONITOR 阶段损伤尚未应用）
        checkAndWarn(player, item, slot, cfg, event.getDamage());
    }

    /**
     * 兜底：玩家受伤时检查所有盔甲槽位。
     * 部分 Paper 版本在盔甲耐久扣减时不触发 PlayerItemDamageEvent。
     * 本路径拿不到精确扣减量，extraDamage 传 0（读扣减前状态）。
     *
     * Folia 兼容：EntityDamageEvent 可能由其他区域线程触发，此时访问玩家背包是不安全的，
     * 因此先用 Bukkit.isOwnedByCurrentRegion 判断当前线程是否拥有该玩家；不是则跳过本次兜底
     * （主路径 PlayerItemDamageEvent 仍会在玩家所属区域线程上正常触发）。
     * 该方法在 Paper/Leaf/Purpur 1.20.5 起就存在（非 Folia 服务端恒为 true）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.hasPermission(HFcatDurabilityAlert.PERMISSION_USE)) return;

        Settings cfg = settings;
        if (!cfg.checkArmor) return;
        if (!ownsEntity(player)) return;

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = player.getInventory().getItem(slot);
            if (armor == null || armor.getType() == Material.AIR) continue;
            if (armor.getAmount() > 1) continue;
            checkAndWarn(player, armor, slot, cfg, 0);
        }
    }

    /**
     * 物品完全损坏时发送最终警告。
     * 尊重 ignore-items（与阈值警告的忽略语义一致）；check-* 开关无法应用——
     * 物品损坏后已从物品栏移除，无法定位原槽位。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(@NotNull PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(HFcatDurabilityAlert.PERMISSION_USE)) return;

        Settings cfg = settings;
        String template = cfg.breakMessage;
        // 留空 = 不提示（也不播放声音），与 config.yml 注释一致
        if (template == null || template.isBlank()) return;

        ItemStack broken = event.getBrokenItem();

        // 与阈值警告一致：忽略列表中的物品断裂也不发消息
        if (isIgnored(broken.getType(), cfg)) return;

        Component itemNameComp = getItemDisplayNameComponent(broken);

        // 替换 {item} 为 Component
        String itemMarker = "_HFCDA_ITEM_";
        Component base = LEGACY_SECTION.deserialize(translateColorCodes(template.replace("{item}", itemMarker)));
        Component finalMsg = base.replaceText(TextReplacementConfig.builder()
                .matchLiteral(itemMarker)
                .replacement(itemNameComp)
                .build());

        sendWarning(player, finalMsg, cfg);
    }

    /** 当前线程是否拥有该实体所在区域（Folia 安全；非 Folia 服务端恒为 true） */
    private static boolean ownsEntity(Player player) {
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            // 极旧服务端或异常情况下保守跳过兜底路径
            return false;
        }
    }

    /** 清理同 tick 去重记录（防内存增长） */
    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        lastWarnNanos.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 核心逻辑：检查物品耐久并发送警告（流程见 DESIGN.md 第六节，级联见类注释）。
     *
     * @param extraDamage 本次事件将要应用的损伤（主监听传 event.getDamage()，兜底传 0）
     */
    private void checkAndWarn(Player player, ItemStack item, EquipmentSlot slot,
                              Settings cfg, int extraDamage) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            debug(cfg, () -> player.getName() + " item has no meta, skipped");
            return;
        }

        // 无耐久物品：非 Damageable 直接跳过
        if (!(meta instanceof Damageable damageable)) {
            debug(cfg, () -> player.getName() + " item " + item.getType() + " has no meta damage, skipped");
            return;
        }

        // 满耐久（damage=0）= 物品已被完全修复：顺手清掉可能残留的警告标记，
        // 否则该标记会一直压制到下次警告阈值重新跨越（例如修复后又从 100% 掉到 50% 只发一条）
        if (!damageable.hasDamage()) {
            if (readLastWarned(player, slot, item) >= 0) {
                removeWarnMarker(player, slot, item);
                debug(cfg, () -> player.getName() + " item " + item.getType() + " fully repaired, warn marker reset");
            }
            return;
        }

        // ✅ 正确读取组件 max_damage（优先），否则回退类型默认值
        int maxDamage = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        if (maxDamage <= 0) {
            debug(cfg, () -> player.getName() + " item " + item.getType() + " is unbreakable (max<=0), skipped");
            return; // 不可破坏或无耐久
        }

        // ✅ damage 是「已损失」，不是剩余；主路径按本次扣减后的状态判定
        int damage = damageable.getDamage() + Math.max(0, extraDamage);
        if (damage > maxDamage) damage = maxDamage;
        int remaining = maxDamage - damage;
        if (remaining <= 0) {
            debug(cfg, () -> player.getName() + " item " + item.getType() + " will break this hit, threshold warn skipped");
            return;
        }

        double percent = (double) remaining / maxDamage * 100.0;

        // 修复重置（必须放在 ignore-items / mending 的提前返回「之前」）：
        // - cooldown=-1：当前百分比回升到上次警告阈值之上 → 清除 PDC 标记（DESIGN 4.2 方案A）
        // - cooldown>0：耐久回升到某阈值之上时清除该阈值的时间戳，使其可重新触发
        // 若放在后面，带修补的物品一旦回升到 mending-only-warn-below 之上就直接 return，
        // 旧标记会一直残留，导致该物品之后再跌回低耐久时「永远不会再告警」。
        int lastWarned = -1;
        if (cfg.repeatable) {
            clearWarnTimesAbove(player, slot, item, percent);
        } else {
            lastWarned = readLastWarned(player, slot, item);
            if (lastWarned >= 0 && percent > lastWarned) {
                final int repairedFrom = lastWarned; // lambda 需要 effectively final 副本
                removeWarnMarker(player, slot, item);
                debug(cfg, () -> player.getName() + " item repaired above " + repairedFrom + "%, warn marker reset");
                lastWarned = -1;
            }
        }

        // 忽略列表
        if (isIgnored(item.getType(), cfg)) {
            debug(cfg, () -> player.getName() + " item " + item.getType() + " in ignore-items, skipped");
            return;
        }

        // 经验修补：有修补的物品仅在达到/低于 mending-only-warn-below 时警告（0 与 -1 均视为禁用）
        boolean mendingActive = cfg.mendingEnabled
                && item.getEnchantmentLevel(Enchantment.MENDING) > 0;
        if (mendingActive && percent > cfg.mendingBelow) {
            debug(cfg, () -> player.getName() + " item " + item.getType() + " has mending, percent=" + (int) percent
                    + " > " + cfg.mendingBelow + ", skipped");
            return;
        }

        // 阈值列表：mending 生效时使用只保留 <= 修补阈值的档位（快照中已排序好）
        List<Integer> thresholds = mendingActive ? cfg.mendingThresholds : cfg.thresholds;
        if (thresholds.isEmpty()) {
            debug(cfg, () -> player.getName() + " no valid thresholds, skipped");
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = cfg.cooldownMillis;

        for (int threshold : thresholds) {
            if (percent > threshold) continue;

            if (cfg.repeatable) {
                // 同阈值冷却中 → 继续检查更低档位（级联）
                Long lastTime = readWarnTime(player, slot, item, threshold);
                if (lastTime != null && now - lastTime < cooldownMillis) {
                    debug(cfg, () -> player.getName() + " item " + item.getType() + " threshold " + threshold
                            + " is on cooldown, try next lower");
                    continue;
                }
            } else if (lastWarned >= 0 && threshold >= lastWarned) {
                // 该档位已警告过 → 继续检查更低档位（级联）
                debug(cfg, () -> player.getName() + " item " + item.getType() + " already warned for threshold "
                        + threshold + ", try next lower");
                continue;
            }

            // 同 tick 去重：兜底事件与主事件同 tick 连发时只发一条
            String dedupKey = player.getUniqueId() + ":" + slot.name();
            long nowNanos = System.nanoTime();
            Long lastWarn = lastWarnNanos.get(dedupKey);
            if (lastWarn != null && nowNanos - lastWarn < DEDUP_WINDOW_NANOS) {
                debug(cfg, () -> player.getName() + " already warned this tick for " + slot + ", skip");
                return;
            }

            sendThresholdWarning(player, item, slot, remaining, maxDamage, (int) percent, threshold, cfg);
            if (cfg.repeatable) {
                markWarnedCooldown(player, slot, item, threshold, now);
            } else {
                markWarnedThreshold(player, slot, item, threshold);
            }
            lastWarnNanos.put(dedupKey, nowNanos);
            debug(cfg, () -> player.getName() + " warned for " + item.getType() + " threshold " + threshold
                    + " percent " + (int) percent);
            return; // 每次事件只发一条警告
        }
    }

    /**
     * 定位物品当前所在的监控槽位。
     * PlayerItemDamageEvent 不携带槽位信息，通过匹配玩家身上的实时物品判断。
     * 返回 null 表示不在监控范围内。
     * 已知局限：主副手同时持同类型、同数量、同已损失耐久物品时无法区分（方案书未要求，MINOR）。
     */
    private @Nullable EquipmentSlot findSlot(Player player, ItemStack item, Settings cfg) {
        if (cfg.checkMainhand && isSameItem(player.getInventory().getItemInMainHand(), item)) {
            return EquipmentSlot.HAND;
        }
        if (cfg.checkOffhand && isSameItem(player.getInventory().getItemInOffHand(), item)) {
            return EquipmentSlot.OFF_HAND;
        }
        if (cfg.checkArmor) {
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                if (isSameItem(player.getInventory().getItem(slot), item)) return slot;
            }
        }
        return null;
    }

    /**
     * 判断两个 ItemStack 是否指向同一件物品（类型 + 数量 + 已损失耐久一致视为同一件）。
     * 事件物品与玩家身上的实物是包装副本，引用比较不可靠，因此比较数值属性。
     */
    private boolean isSameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        return a.getType() == b.getType()
                && a.getAmount() == b.getAmount()
                && damageOf(a) == damageOf(b);
    }

    private int damageOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return (meta instanceof Damageable damageable) ? damageable.getDamage() : 0;
    }

    /** 检查物品是否在忽略列表中（快照里已统一为小写、去 minecraft: 前缀） */
    private boolean isIgnored(Material material, Settings cfg) {
        return !cfg.ignoreItems.isEmpty() && cfg.ignoreItems.contains(material.name().toLowerCase(Locale.ROOT));
    }

    // ---------- PDC 防重复（写入玩家身上真实物品） ----------

    /** 读取时优先取玩家身上真实物品；匹配失败时回退事件物品（两者共享 NMS handle） */
    private ItemStack liveItemOrFallback(Player player, EquipmentSlot slot, ItemStack item) {
        ItemStack live = liveItemInSlot(player, slot);
        if (isSameItem(live, item)) return live;
        return item;
    }

    /** 取玩家槽位中的实时物品 */
    private ItemStack liveItemInSlot(Player player, EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            default -> player.getInventory().getItem(slot);
        };
    }

    /** 读取上次警告的阈值（无标记返回 -1） */
    private int readLastWarned(Player player, EquipmentSlot slot, ItemStack item) {
        ItemMeta meta = liveItemOrFallback(player, slot, item).getItemMeta();
        if (meta == null) return -1;
        return meta.getPersistentDataContainer().getOrDefault(lastWarnKey, PersistentDataType.INTEGER, -1);
    }

    /** 清除警告标记（物品被修复时调用） */
    private void removeWarnMarker(Player player, EquipmentSlot slot, ItemStack item) {
        liveItemOrFallback(player, slot, item)
                .editMeta(meta -> {
                    meta.getPersistentDataContainer().remove(lastWarnKey);
                    meta.getPersistentDataContainer().remove(lastWarnTimesKey);
                });
    }

    /**
     * 在玩家身上真实物品的 PDC 中记录已警告的阈值。
     * 同时清理 cooldown>0 模式遗留的时间戳，避免配置切回 -1 后残留旧状态。
     * 直接改事件里的 ItemStack 元数据是包装副本、不可靠，必须写回实物。
     */
    private void markWarnedThreshold(Player player, EquipmentSlot slot, ItemStack item, int threshold) {
        liveItemOrFallback(player, slot, item)
                .editMeta(meta -> {
                    meta.getPersistentDataContainer()
                            .set(lastWarnKey, PersistentDataType.INTEGER, threshold);
                    meta.getPersistentDataContainer().remove(lastWarnTimesKey);
                });
    }

    // ---------- 同阈值冷却时间戳（cooldown > 0 时使用） ----------

    /** 读取某阈值最近一次警告的时间戳（无记录返回 null） */
    private @Nullable Long readWarnTime(Player player, EquipmentSlot slot, ItemStack item, int threshold) {
        Map<Integer, Long> times = parseWarnTimes(readWarnTimesRaw(player, slot, item));
        return times.get(threshold);
    }

    /** 读取冷却时间戳原始字符串（无记录返回 null） */
    private @Nullable String readWarnTimesRaw(Player player, EquipmentSlot slot, ItemStack item) {
        ItemMeta meta = liveItemOrFallback(player, slot, item).getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(lastWarnTimesKey, PersistentDataType.STRING);
    }

    /**
     * 记录某阈值的警告时间戳（cooldown > 0）。
     * 同时保留 last_warn_threshold 整数标记，便于配置切回 -1 时仍有一致的一次性状态。
     */
    private void markWarnedCooldown(Player player, EquipmentSlot slot, ItemStack item, int threshold, long now) {
        Map<Integer, Long> times = parseWarnTimes(readWarnTimesRaw(player, slot, item));
        times.put(threshold, now);
        String serialized = serializeWarnTimes(times);
        liveItemOrFallback(player, slot, item)
                .editMeta(meta -> {
                    meta.getPersistentDataContainer().set(lastWarnTimesKey, PersistentDataType.STRING, serialized);
                    meta.getPersistentDataContainer().set(lastWarnKey, PersistentDataType.INTEGER, threshold);
                });
    }

    /** 耐久回升到某阈值之上时，清除该阈值的冷却时间戳，使其可重新触发 */
    private void clearWarnTimesAbove(Player player, EquipmentSlot slot, ItemStack item, double percent) {
        String data = readWarnTimesRaw(player, slot, item);
        if (data == null || data.isEmpty()) return;
        Map<Integer, Long> times = parseWarnTimes(data);
        boolean changed = times.keySet().removeIf(t -> percent > t);
        if (!changed) return;
        ItemStack live = liveItemOrFallback(player, slot, item);
        if (times.isEmpty()) {
            live.editMeta(meta -> {
                meta.getPersistentDataContainer().remove(lastWarnTimesKey);
                meta.getPersistentDataContainer().remove(lastWarnKey);
            });
        } else {
            int lowestWarned = Integer.MAX_VALUE;
            for (int t : times.keySet()) {
                lowestWarned = Math.min(lowestWarned, t);
            }
            final int legacyMarker = lowestWarned;
            final String serialized = serializeWarnTimes(times);
            live.editMeta(meta -> {
                meta.getPersistentDataContainer().set(lastWarnTimesKey, PersistentDataType.STRING, serialized);
                meta.getPersistentDataContainer().set(lastWarnKey, PersistentDataType.INTEGER, legacyMarker);
            });
        }
    }

    /** 解析 PDC 中的阈值→时间戳映射（格式: "50=1710000000123,30=1710000000456"） */
    private static Map<Integer, Long> parseWarnTimes(@Nullable String data) {
        Map<Integer, Long> times = new HashMap<>();
        if (data == null || data.isEmpty()) return times;
        for (String entry : data.split(",")) {
            int sep = entry.indexOf('=');
            if (sep <= 0) continue;
            try {
                times.put(Integer.parseInt(entry.substring(0, sep)),
                        Long.parseLong(entry.substring(sep + 1)));
            } catch (NumberFormatException ignored) {
                // 跳过损坏的旧数据
            }
        }
        return times;
    }

    /** 序列化阈值→时间戳映射为 PDC STRING */
    private static String serializeWarnTimes(Map<Integer, Long> times) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Long> entry : times.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ---------- 消息输出 ----------

    /** 发送阈值警告消息 */
    private void sendThresholdWarning(Player player, ItemStack item, EquipmentSlot slot,
                                      int remaining, int max, int percent, int threshold, Settings cfg) {
        String template = cfg.messages.get(threshold);
        if (template == null) template = cfg.defaultMessage;
        if (template == null || template.isEmpty()) {
            debug(cfg, () -> player.getName() + " no message configured for threshold " + threshold);
            return;
        }

        String itemMarker = "_HFCDA_ITEM_";
        // 先用占位符替换普通字符串占位，{item} 用特殊标记，后续替换为 Component
        String msg = template
                .replace("{item}", itemMarker)
                .replace("{slot}", getSlotName(slot))
                .replace("{durability}", String.valueOf(remaining))
                .replace("{max}", String.valueOf(max))
                .replace("{percent}", String.valueOf(percent))
                .replace("{threshold}", String.valueOf(threshold));

        // 解析为 Adventure Component，然后替换 {item} 标记为物品名 Component
        Component base = LEGACY_SECTION.deserialize(translateColorCodes(msg));
        Component finalMsg = base.replaceText(TextReplacementConfig.builder()
                .matchLiteral(itemMarker)
                .replacement(getItemDisplayNameComponent(item))
                .build());

        sendWarning(player, finalMsg, cfg);
    }

    /**
     * 发送警告消息到玩家。
     * 全部使用 Adventure API（Paper 下 Bungee/ActionBar/Title 旧版 API 已弃用）。
     */
    private void sendWarning(Player player, Component component, Settings cfg) {
        if (cfg.sendChat) {
            player.sendMessage(component);
        }
        if (cfg.sendActionbar) {
            player.sendActionBar(component);
        }
        if (cfg.sendTitle) {
            player.showTitle(Title.title(component, Component.empty(),
                    Title.Times.times(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT)));
        }
        // 声音是独立输出通道，不受 send-* 开关影响
        if (cfg.sound != null) {
            player.playSound(player.getLocation(), cfg.sound, cfg.soundVolume, cfg.soundPitch);
        }
    }

    /** 解析声音参数并钳制到 [min,max]；NaN/非法数字回退默认值 */
    private static float parseSoundParam(String raw, float min, float max) {
        try {
            float value = Float.parseFloat(raw);
            if (Float.isNaN(value)) return 1.0f;
            return Math.clamp(value, min, max);
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    /**
     * 获取物品显示名称的 Adventure Component。
     * 优先级：自定义名 > 默认物品名（TranslatableComponent，客户端自行翻译）。
     * 这样无命名物品自动显示客户端语言（如中文「铁头盔」），数据包的翻译键也能正确渲染。
     */
    private Component getItemDisplayNameComponent(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        // 1) 自定义名（铁砧重命名或 /data）优先
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName();
        }
        // 2) 数据包物品的自定义 item_name（如 Stellarity 的翻译键）
        if (meta != null && meta.hasItemName()) {
            return meta.itemName();
        }
        // 3) 默认：使用物品的翻译键，客户端自动按语言渲染
        // getItemTranslationKey() 可能为 null（非物品 Material），此时回退到英文名
        String key = item.getType().getItemTranslationKey();
        return key != null ? Component.translatable(key) : Component.text(formatMaterialName(item.getType()));
    }

    /** 将 Material 枚举名转为 Title Case（如 IRON_HELMET → "Iron Helmet"） */
    private static String formatMaterialName(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(' ');
            }
        }
        return sb.toString().trim();
    }

    /** 获取槽位中文名称 */
    private String getSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头盔";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case FEET -> "靴子";
            case HAND -> "主手";
            case OFF_HAND -> "副手";
            default -> slot.name();
        };
    }

    /**
     * 把 & 颜色代码翻译成 § 代码（兼容 &#RRGGBB 十六进制），
     * 替代已弃用的 ChatColor.translateAlternateColorCodes。
     */
    private static String translateColorCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder out = new StringBuilder(chars.length + 16);
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '&' && i + 1 < chars.length) {
                char next = chars[i + 1];
                if (next == '#') {
                    // 十六进制格式 &#RRGGBB → §x§R§R§G§G§B§B
                    if (i + 7 < chars.length
                            && isHex(chars[i + 2]) && isHex(chars[i + 3]) && isHex(chars[i + 4])
                            && isHex(chars[i + 5]) && isHex(chars[i + 6]) && isHex(chars[i + 7])) {
                        out.append("§x");
                        for (int j = i + 2; j <= i + 7; j++) {
                            out.append('§').append(chars[j]);
                        }
                        i += 7;
                        continue;
                    }
                } else if (isColorCodeChar(next)) {
                    out.append('§').append(next);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isColorCodeChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == 'k' || c == 'K'
                || c == 'l' || c == 'L'
                || c == 'm' || c == 'M'
                || c == 'n' || c == 'N'
                || c == 'o' || c == 'O'
                || c == 'r' || c == 'R';
    }

    /** debug 开关下的日志输出（惰性求值：开关关闭时不执行字符串拼接） */
    private void debug(Settings cfg, Supplier<String> messageSupplier) {
        if (cfg.debug) {
            plugin.getLogger().info("[Debug] " + messageSupplier.get());
        }
    }

    // ---------- 配置快照 ----------

    /**
     * 不可变配置快照：onEnable / reload 时一次性解析，事件链只读取这里的字段。
     * 好处：事件热路径零配置解析开销，且 reload 后所有事件看到的是同一份完整配置。
     */
    private static final class Settings {

        final List<Integer> thresholds;
        final List<Integer> mendingThresholds;
        final boolean mendingEnabled;
        final int mendingBelow;
        final boolean repeatable;
        final long cooldownMillis;
        final boolean checkArmor;
        final boolean checkMainhand;
        final boolean checkOffhand;
        final Set<String> ignoreItems;
        final Map<Integer, String> messages;
        final String defaultMessage;
        final String breakMessage;
        final boolean sendChat;
        final boolean sendActionbar;
        final boolean sendTitle;
        final @Nullable Sound sound;
        final float soundVolume;
        final float soundPitch;
        final boolean debug;

        private Settings(FileConfiguration config) {
            // 阈值：过滤越界值 → 降序
            List<Integer> list = new ArrayList<>(config.getIntegerList("warnings.thresholds"));
            list.removeIf(t -> t < HFcatDurabilityAlert.THRESHOLD_MIN || t > HFcatDurabilityAlert.THRESHOLD_MAX);
            list.sort((a, b) -> b - a);
            this.thresholds = Collections.unmodifiableList(list);

            this.mendingBelow = config.getInt("mending.mending-only-warn-below", HFcatDurabilityAlert.NO_MENDING);
            this.mendingEnabled = this.mendingBelow > 0;
            if (mendingEnabled) {
                List<Integer> mending = new ArrayList<>(list);
                mending.removeIf(t -> t > mendingBelow);
                this.mendingThresholds = Collections.unmodifiableList(mending);
            } else {
                this.mendingThresholds = this.thresholds;
            }

            int cooldown = config.getInt("warnings.cooldown", -1);
            this.repeatable = cooldown > 0;
            this.cooldownMillis = repeatable ? cooldown * 1000L : 0L;

            this.checkArmor = config.getBoolean("warnings.check-armor", true);
            this.checkMainhand = config.getBoolean("warnings.check-mainhand", true);
            this.checkOffhand = config.getBoolean("warnings.check-offhand", true);

            Set<String> ignored = new HashSet<>();
            for (String raw : config.getStringList("warnings.ignore-items")) {
                if (raw == null) continue;
                String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (!normalized.isEmpty()) ignored.add(normalized);
            }
            this.ignoreItems = Collections.unmodifiableSet(ignored);

            Map<Integer, String> formats = new LinkedHashMap<>();
            for (Map<?, ?> entry : config.getMapList("messages.formats")) {
                Object pct = entry.get("percent");
                Object msg = entry.get("message");
                if (pct instanceof Number number && msg instanceof String text) {
                    formats.putIfAbsent(number.intValue(), text);
                }
            }
            this.messages = Collections.unmodifiableMap(formats);
            this.defaultMessage = DEFAULT_MESSAGE;

            String breakTemplate = config.getString("messages.break-message", "");
            this.breakMessage = (breakTemplate == null || breakTemplate.isBlank()) ? null : breakTemplate;

            this.sendChat = config.getBoolean("messages.send-chat", true);
            this.sendActionbar = config.getBoolean("messages.send-actionbar", true);
            this.sendTitle = config.getBoolean("messages.send-title", false);

            String soundStr = config.getString("messages.warning-sound", "");
            if (soundStr == null || soundStr.isBlank()) {
                this.sound = null;
                this.soundVolume = 1.0f;
                this.soundPitch = 1.0f;
            } else {
                Sound resolved = SoundResolver.resolve(SoundResolver.extractName(soundStr));
                this.sound = resolved;
                String[] parts = soundStr.split(":", -1);
                int paramStart = SoundResolver.paramStart(soundStr);
                this.soundVolume = parts.length > paramStart
                        ? parseSoundParam(parts[paramStart], SOUND_VOLUME_MIN, SOUND_VOLUME_MAX)
                        : 1.0f;
                this.soundPitch = parts.length > paramStart + 1
                        ? parseSoundParam(parts[paramStart + 1], SOUND_PITCH_MIN, SOUND_PITCH_MAX)
                        : 1.0f;
            }

            this.debug = config.getBoolean("debug", false);
        }

        static Settings from(FileConfiguration config) {
            return new Settings(config);
        }
    }
}
