package io.github.fcestial.hfcatdurabilityalert;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * - 每个阈值只警告一次：降序遍历阈值，已警告的档位 continue 到更低档，命中未警告档位后警告并结束本次事件
 *   （注：DESIGN.md 第六节流程图写的是「已警告→结束」，与第四节「每个阈值各触发一次」意图矛盾，
 *    本实现按第四节意图采用 continue 级联，见 MEMORY.md）
 * - 物品被修复（当前百分比回升到上次警告阈值之上）时清除 PDC 标记，允许重新触发（DESIGN 4.2 方案A）
 * - 同 tick 内同一玩家同一槽位只发一条警告（EntityDamageEvent 兜底与 PlayerItemDamageEvent
 *   同 tick 连续触发时，防止一次损伤跨两个阈值连发两条）
 *
 * 线程约定：本插件面向 Paper/Leaf 非 Folia 服务器，所有事件处理器都在主线程执行，
 * lastWarnTick 为普通 HashMap（主线程独占）。若未来迁移 Folia，需改用区域线程安全方案
 * （如 RegionizedData 或 ConcurrentHashMap + 区域调度），并替换 Bukkit.getCurrentTick()。
 *
 * 消息输出（Paper 26.2 无弃用 API）：
 * - & 颜色代码 → translateColorCodes（兼容 &#RRGGBB）→ LegacyComponentSerializer
 *   （显式启用 hexColors + useUnusualXRepeatedCharacterHexFormat，不依赖运行时 Provider 注入）
 * - sendMessage / sendActionBar / showTitle / Registry.SOUND_EVENT
 */
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

    private final HFcatDurabilityAlert plugin;
    private final NamespacedKey lastWarnKey;

    /** 同 tick 去重：playerUuid:slotName → server tick（主线程独占，见类注释） */
    private final Map<String, Integer> lastWarnTick = new HashMap<>();

    public DurabilityListener(HFcatDurabilityAlert plugin) {
        this.plugin = plugin;
        this.lastWarnKey = new NamespacedKey("hfcatdurabilityalert", "last_warn_threshold");
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
        FileConfiguration config = plugin.getConfig();

        // 跳过堆叠物品
        if (item.getAmount() > 1) return;

        EquipmentSlot slot = findSlot(player, item, config);
        if (slot == null) return; // 不在监控范围内

        // 主路径：用 event.getDamage() 预测扣减后的耐久再判定（MONITOR 阶段损伤尚未应用）
        checkAndWarn(player, item, slot, config, event.getDamage());
    }

    /**
     * 兜底：玩家受伤时检查所有盔甲槽位。
     * 部分 Paper 版本在盔甲耐久扣减时不触发 PlayerItemDamageEvent。
     * 本路径拿不到精确扣减量，extraDamage 传 0（读扣减前状态）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.hasPermission(HFcatDurabilityAlert.PERMISSION_USE)) return;

        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("warnings.check-armor", true)) return;

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = player.getInventory().getItem(slot);
            if (armor == null || armor.getType() == Material.AIR) continue;
            if (armor.getAmount() > 1) continue;
            checkAndWarn(player, armor, slot, config, 0);
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

        ItemStack broken = event.getBrokenItem();
        FileConfiguration config = plugin.getConfig();

        // 与阈值警告一致：忽略列表中的物品断裂也不发消息
        if (isIgnored(broken.getType(), config)) return;

        Component itemNameComp = getItemDisplayNameComponent(broken);
        String template = config.getString("messages.break-message",
                "&4&l!!! &f{item} &4&l已损坏！");

        // 替换 {item} 为 Component
        String ITEM_MARKER = "_HFCDA_ITEM_";
        String msg = template.replace("{item}", ITEM_MARKER);
        Component base = LEGACY_SECTION.deserialize(translateColorCodes(msg));
        Component finalMsg = base.replaceText(
                TextReplacementConfig.builder()
                        .matchLiteral(ITEM_MARKER)
                        .replacement(itemNameComp)
                        .build()
        );

        sendWarning(player, finalMsg, config);
    }

    /** 清理同 tick 去重记录（防内存增长） */
    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        lastWarnTick.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 核心逻辑：检查物品耐久并发送警告（流程见 DESIGN.md 第六节，级联见类注释）。
     *
     * @param extraDamage 本次事件将要应用的损伤（主监听传 event.getDamage()，兜底传 0）
     * @return 是否发送了警告（供调试）
     */
    private boolean checkAndWarn(Player player, ItemStack item, EquipmentSlot slot,
                                 FileConfiguration config, int extraDamage) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            debug(() -> player.getName() + " item has no meta, skipped");
            return false;
        }

        // 无耐久物品：非 Damageable 或没有损伤值时跳过
        if (!(meta instanceof Damageable damageable) || !damageable.hasDamage()) {
            debug(() -> player.getName() + " item " + item.getType() + " has no durability damage, skipped");
            return false;
        }

        // ✅ 正确读取组件 max_damage（优先），否则回退类型默认值
        int maxDamage = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        if (maxDamage <= 0) {
            debug(() -> player.getName() + " item " + item.getType() + " is unbreakable (max<=0), skipped");
            return false; // 不可破坏或无耐久
        }

        // ✅ damage 是「已损失」，不是剩余；主路径按本次扣减后的状态判定
        int damage = damageable.getDamage() + Math.max(0, extraDamage);
        if (damage > maxDamage) damage = maxDamage;
        int remaining = maxDamage - damage;
        if (remaining <= 0) {
            debug(() -> player.getName() + " item " + item.getType() + " will break this hit, threshold warn skipped");
            return false;
        }

        double percent = (double) remaining / maxDamage * 100.0;

        // 忽略列表
        if (isIgnored(item.getType(), config)) {
            debug(() -> player.getName() + " item " + item.getType() + " in ignore-items, skipped");
            return false;
        }

        // 经验修补：有修补的物品仅在达到/低于 mending-only-warn-below 时警告（0 与 -1 均视为禁用）
        int mendingLevel = item.getEnchantmentLevel(Enchantment.MENDING);
        int mendingOnlyBelow = config.getInt("mending.mending-only-warn-below",
                HFcatDurabilityAlert.NO_MENDING);
        boolean mendingActive = mendingLevel > 0 && mendingOnlyBelow > 0;
        if (mendingActive && percent > mendingOnlyBelow) {
            debug(() -> player.getName() + " item " + item.getType() + " has mending, percent=" + (int) percent
                    + " > " + mendingOnlyBelow + ", skipped");
            return false;
        }

        // 修复重置（DESIGN 4.2 方案A）：当前百分比回升到上次警告阈值之上 → 清除标记
        // 注意：重置后可能立即以「最高 <= 当前百分比的档位」重发警告（如 30% 修复到 45% 会触发 50 档），
        // 这是方案书 4.2 方案A 的既定语义，已在 MEMORY.md 记录
        int lastWarned = readLastWarned(player, slot, item);
        if (lastWarned >= 0 && percent > lastWarned) {
            final int repairedFrom = lastWarned; // lambda 需要 effectively final 副本
            removeWarnMarker(player, slot, item);
            debug(() -> player.getName() + " item repaired above " + repairedFrom + "%, warn marker reset");
            lastWarned = -1;
        }

        // 阈值列表：过滤越界值 → 降序 → mending 生效时只保留 <= 修补阈值的档位
        List<Integer> thresholds = new ArrayList<>(config.getIntegerList("warnings.thresholds"));
        thresholds.removeIf(t -> t < HFcatDurabilityAlert.THRESHOLD_MIN
                || t > HFcatDurabilityAlert.THRESHOLD_MAX);
        if (mendingActive) {
            thresholds.removeIf(t -> t > mendingOnlyBelow);
        }
        thresholds.sort((a, b) -> b - a); // 降序
        if (thresholds.isEmpty()) {
            debug(() -> player.getName() + " no valid thresholds, skipped");
            return false;
        }

        for (int threshold : thresholds) {
            if (percent <= threshold) {
                // 该档位已警告过 → 继续检查更低档位（级联）
                if (lastWarned >= 0 && threshold >= lastWarned) {
                    debug(() -> player.getName() + " item " + item.getType() + " already warned for threshold "
                            + threshold + ", try next lower");
                    continue;
                }
                // 同 tick 去重：兜底事件与主事件同 tick 连发时只发一条
                String dedupKey = player.getUniqueId() + ":" + slot.name();
                int tick = Bukkit.getCurrentTick();
                if (lastWarnTick.getOrDefault(dedupKey, -1) == tick) {
                    debug(() -> player.getName() + " already warned this tick for " + slot + ", skip");
                    return false;
                }

                sendThresholdWarning(player, item, slot, remaining, maxDamage, (int) percent, threshold, config);
                markWarnedThreshold(player, slot, item, threshold);
                lastWarnTick.put(dedupKey, tick);
                debug(() -> player.getName() + " warned for " + item.getType() + " threshold " + threshold
                        + " percent " + (int) percent);
                return true; // 每次事件只发一条警告
            }
        }
        return false;
    }

    /**
     * 定位物品当前所在的监控槽位。
     * PlayerItemDamageEvent 不携带槽位信息，通过匹配玩家身上的实时物品判断。
     * 返回 null 表示不在监控范围内。
     * 已知局限：主副手同时持同类型、同数量、同已损失耐久物品时无法区分（方案书未要求，MINOR）。
     */
    private @Nullable EquipmentSlot findSlot(Player player, ItemStack item, FileConfiguration config) {
        boolean checkArmor = config.getBoolean("warnings.check-armor", true);
        boolean checkMainhand = config.getBoolean("warnings.check-mainhand", true);
        boolean checkOffhand = config.getBoolean("warnings.check-offhand", true);

        if (checkMainhand && isSameItem(player.getInventory().getItemInMainHand(), item)) return EquipmentSlot.HAND;
        if (checkOffhand && isSameItem(player.getInventory().getItemInOffHand(), item)) return EquipmentSlot.OFF_HAND;

        if (checkArmor) {
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

    /** 检查物品是否在忽略列表中（支持带 minecraft: 前缀与不带） */
    private boolean isIgnored(Material material, FileConfiguration config) {
        List<String> ignored = config.getStringList("warnings.ignore-items");
        for (String item : ignored) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            String normalized = trimmed.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (material.name().equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    // ---------- PDC 防重复（写入玩家身上真实物品） ----------

    /** 读取时优先取玩家身上真实物品；匹配失败时回退事件物品（两者共享 NMS handle） */
    private ItemStack liveItemOrFallback(Player player, EquipmentSlot slot, ItemStack item) {
        ItemStack live = liveItemInSlot(player, slot);
        if (live != null && isSameItem(live, item)) return live;
        return item;
    }

    /** 取玩家槽位中的实时物品 */
    private @Nullable ItemStack liveItemInSlot(Player player, EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            default -> player.getInventory().getItem(slot);
        };
    }

    /** 物品上是否已有警告标记 */
    private boolean hasWarnMarker(Player player, EquipmentSlot slot, ItemStack item) {
        ItemMeta meta = liveItemOrFallback(player, slot, item).getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(lastWarnKey, PersistentDataType.INTEGER);
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
                .editMeta(meta -> meta.getPersistentDataContainer().remove(lastWarnKey));
    }

    /**
     * 在玩家身上真实物品的 PDC 中记录已警告的阈值。
     * 直接改事件里的 ItemStack 元数据是包装副本、不可靠，必须写回实物。
     */
    private void markWarnedThreshold(Player player, EquipmentSlot slot, ItemStack item, int threshold) {
        liveItemOrFallback(player, slot, item)
                .editMeta(meta -> meta.getPersistentDataContainer()
                        .set(lastWarnKey, PersistentDataType.INTEGER, threshold));
    }

    // ---------- 消息输出 ----------

    /** 发送阈值警告消息 */
    private void sendThresholdWarning(Player player, ItemStack item, EquipmentSlot slot,
                                     int remaining, int max, int percent, int threshold,
                                     FileConfiguration config) {
        Component itemNameComp = getItemDisplayNameComponent(item);
        String slotName = getSlotName(slot);

        String message = findThresholdMessage(threshold, config);
        if (message == null || message.isEmpty()) {
            debug(() -> player.getName() + " no message configured for threshold " + threshold);
            return;
        }

        // 先用占位符替换普通字符串占位，{item} 用特殊标记，后续替换为 Component
        String ITEM_MARKER = "_HFCDA_ITEM_";
        String msg = message
                .replace("{item}", ITEM_MARKER)
                .replace("{slot}", slotName)
                .replace("{durability}", String.valueOf(remaining))
                .replace("{max}", String.valueOf(max))
                .replace("{percent}", String.valueOf(percent))
                .replace("{threshold}", String.valueOf(threshold));

        // 解析为 Adventure Component，然后替换 {item} 标记为物品名 Component
        Component base = LEGACY_SECTION.deserialize(translateColorCodes(msg));
        Component finalMsg = base.replaceText(
                TextReplacementConfig.builder()
                        .matchLiteral(ITEM_MARKER)
                        .replacement(itemNameComp)
                        .build()
        );

        sendWarning(player, finalMsg, config);
    }

    /** 从配置中查找阈值对应的消息格式，找不到则用通用回退 */
    private @Nullable String findThresholdMessage(int threshold, FileConfiguration config) {
        var section = config.getMapList("messages.formats");
        for (var entry : section) {
            Object pct = entry.get("percent");
            Object msg = entry.get("message");
            if (pct instanceof Number n && msg instanceof String s) {
                if (n.intValue() == threshold) return s;
            }
        }
        // 回退默认消息
        return "&c⚠ {item} 耐久度 {percent}%！ ({durability}/{max})";
    }

    /**
     * 发送警告消息到玩家。
     * 全部使用 Adventure API（Paper 26.2 下 Bungee/ActionBar/Title 旧版 API 已弃用）。
     * 接受已构建好的 Component（含 TranslatableComponent，客户端自行翻译物品名）。
     */
    private void sendWarning(Player player, Component component, FileConfiguration config) {
        if (config.getBoolean("messages.send-chat", true)) {
            player.sendMessage(component);
        }
        if (config.getBoolean("messages.send-actionbar", true)) {
            player.sendActionBar(component);
        }
        if (config.getBoolean("messages.send-title", false)) {
            player.showTitle(Title.title(component, Component.empty(),
                    Title.Times.times(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT)));
        }

        // 声音是独立输出通道，不受 send-* 开关影响
        String soundStr = config.getString("messages.warning-sound", "");
        if (soundStr != null && !soundStr.isEmpty()) {
            playSound(player, soundStr);
        }
    }

    /**
     * 解析并播放声音。格式: SOUND_NAME 或 SOUND_NAME:音量:音调。
     * Sound.valueOf(String) 在 26.2 已标记待删除，改用 Registry.SOUND_EVENT 查询。
     * 对配置错误（前导冒号、非法数字、越界值）只告警或钳制，绝不向事件链抛出异常。
     */
    private void playSound(Player player, String soundStr) {
        String[] parts = soundStr.split(":", -1);
        if (parts[0].isEmpty()) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("无效的声音配置（缺少声音名）: " + soundStr);
            }
            return;
        }
        try {
            NamespacedKey key = NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT));
            Sound sound = Registry.SOUND_EVENT.get(key);
            if (sound == null) {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().warning("未找到声音: " + parts[0]);
                }
                return;
            }
            float volume = parts.length > 1
                    ? parseSoundParam(parts[1], SOUND_VOLUME_MIN, SOUND_VOLUME_MAX, 1.0f)
                    : 1.0f;
            float pitch = parts.length > 2
                    ? parseSoundParam(parts[2], SOUND_PITCH_MIN, SOUND_PITCH_MAX, 1.0f)
                    : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("无效的声音配置: " + soundStr + " (" + e.getMessage() + ")");
            }
        }
    }

    /** 解析声音参数并钳制到 [min,max]；NaN/非法数字回退默认值 */
    private static float parseSoundParam(String raw, float min, float max, float fallback) {
        try {
            float value = Float.parseFloat(raw);
            if (Float.isNaN(value)) return fallback;
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 获取物品显示名称的 Adventure Component。
     * 优先级：自定义名 > 默认物品名（TranslatableComponent，客户端自行翻译）。
     * 这样无命名物品自动显示客户端语言（如中文「铁头盔」），数据包的翻译键也能正确渲染。
     */
    private Component getItemDisplayNameComponent(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Component.text(formatMaterialName(item.getType()));

        // 1) 自定义名（铁砧重命名或 /data）
        if (meta.hasDisplayName()) {
            return meta.displayName();
        }

        // 2) 默认物品名：TranslatableComponent，客户端按语言渲染
        //    数据包物品的翻译键（如 "stellarity:item.hallowed_helmet"）也由客户端负责解析
        Component defaultName = meta.itemName();
        return defaultName != null ? defaultName : Component.text(formatMaterialName(item.getType()));
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
    private void debug(Supplier<String> messageSupplier) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[Debug] " + messageSupplier.get());
        }
    }
}
