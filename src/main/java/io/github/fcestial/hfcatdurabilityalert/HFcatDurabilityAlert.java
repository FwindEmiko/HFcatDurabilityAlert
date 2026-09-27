package io.github.fcestial.hfcatdurabilityalert;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * HFcatDurabilityAlert 插件主类
 *
 * 装备耐久度警告插件，基于 Paper 1.20.5+ 组件系统精确读取耐久，
 * 运行时兼容 Paper / Leaf / Purpur 等 Paper 系分支的 1.20.5 ~ 26.2（Java 21 与 Java 25 服务端均可）。
 *
 * 核心原理：
 * - 1.20.5 起旧版 NBT {@code Damage: short} 被组件 {@code minecraft:damage} / {@code minecraft:max_damage} 取代
 * - Paper API 的 {@code Damageable.getDamage()} 返回「已损失」耐久（非剩余）
 * - {@code Damageable.getMaxDamage()} 自动读取 max_damage 组件（如 Stellarity 把铁头盔覆盖为 407）
 * - 剩余耐久 = getMaxDamage() - getDamage()
 *
 * 触发机制：PlayerItemDamageEvent（精确到单次耐久扣减，无需轮询）
 *           + EntityDamageEvent（盔甲兜底）+ PlayerItemBreakEvent（最终警告）
 */
public class HFcatDurabilityAlert extends JavaPlugin {

    public static final String PERMISSION_USE = "hfcatdurabilityalert.use";
    public static final String PERMISSION_ADMIN = "hfcatdurabilityalert.admin";

    /** 阈值百分比合法范围 */
    public static final int THRESHOLD_MIN = 0;
    public static final int THRESHOLD_MAX = 100;

    /** mending-only-warn-below 的禁用哨兵值（0 与 -1 均视为禁用） */
    public static final int NO_MENDING = -1;

    /** 事件监听器（持有配置快照，reload 时刷新） */
    private DurabilityListener listener;

    @Override
    public void onEnable() {
        // 运行环境能力探测（正常情况下 plugin.yml 的 api-version=1.20.5 已挡住旧服务端）
        if (!ensureRuntimeSupported()) {
            return;
        }

        saveDefaultConfig();
        validateConfig();

        // 注册事件监听器（构造时即建立配置快照）
        listener = new DurabilityListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        // 注册命令（executor 与 tabCompleter 复用同一无状态实例）
        var cmd = getCommand("hfcatdurabilityalert");
        if (cmd != null) {
            var handler = new AlertCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        int thresholds = getConfig().getIntegerList("warnings.thresholds").size();
        getLogger().info("HFcatDurabilityAlert 已启用！共 " + thresholds + " 个警告阈值，监控装备耐久中...");
        getLogger().info("运行环境: " + Bukkit.getName() + " " + Bukkit.getBukkitVersion()
                + " (MC " + Bukkit.getMinecraftVersion() + ", Java " + System.getProperty("java.version") + ")"
                + " | 声音解析: " + SoundResolver.mode());
    }

    @Override
    public void onDisable() {
        getLogger().info("HFcatDurabilityAlert 已禁用。");
    }

    /** 重载配置并做合法性校验 */
    public void reload() {
        reloadConfig();
        validateConfig();
        if (listener != null) {
            // 重建不可变配置快照，保证 reload 后事件链看到的是完整一致的新配置
            listener.refresh();
        }
        getLogger().info("配置已重载。");
    }

    /**
     * 运行环境能力探测：本插件依赖 1.20.5 引入的组件化耐久 API。
     * plugin.yml 的 api-version 已能拦住旧服务端，这里再兜一层，
     * 避免某些分支绕过 api-version 校验后在事件链里抛 NoSuchMethodError。
     *
     * @return 环境可用时返回 true；不可用则自动停用插件并返回 false
     */
    private boolean ensureRuntimeSupported() {
        try {
            Class.forName("org.bukkit.inventory.meta.Damageable").getMethod("hasMaxDamage");
            return true;
        } catch (Throwable ignored) {
            getLogger().severe("当前服务端缺少 1.20.5+ 组件化耐久 API（ItemMeta#hasMaxDamage），插件已自动停用。");
            getLogger().severe("请使用 Paper / Leaf / Purpur 等 Paper 系服务端的 1.20.5 及以上版本。");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    /**
     * 配置合法性校验（只告警，不修改文件）：
     * - 阈值必须在 0~100 之间
     * - cooldown 支持 -1（每个阈值只警告一次）或正数（同阈值冷却 N 秒后重复警告）
     */
    private void validateConfig() {
        List<Integer> thresholds = getConfig().getIntegerList("warnings.thresholds");
        if (thresholds.isEmpty()) {
            getLogger().warning("warnings.thresholds 为空，插件将不会发送任何阈值警告。");
        }
        for (int t : thresholds) {
            if (t < THRESHOLD_MIN || t > THRESHOLD_MAX) {
                getLogger().warning("warnings.thresholds 中存在超出 " + THRESHOLD_MIN + "~" + THRESHOLD_MAX
                        + " 的阈值: " + t + "（将被忽略）");
            } else if (t == 0) {
                getLogger().warning("warnings.thresholds 中的阈值 0 永远不会触发（剩余耐久为 0 时物品已损坏）。");
            }
        }

        int cooldown = getConfig().getInt("warnings.cooldown", -1);
        if (cooldown > 0) {
            getLogger().info("warnings.cooldown=" + cooldown + "：同一阈值每 " + cooldown + " 秒可重复警告。");
        } else if (cooldown == 0 || cooldown < -1) {
            getLogger().warning("warnings.cooldown=" + cooldown + " 无效（支持 -1 或正数），将按 -1 处理"
                    + "（每个阈值只警告一次）。");
        }

        // mending 特殊处理校验
        int mendingBelow = getConfig().getInt("mending.mending-only-warn-below", NO_MENDING);
        if (mendingBelow > 100) {
            getLogger().warning("mending.mending-only-warn-below=" + mendingBelow
                    + " 超出 0~100，所有通用阈值都会生效（等效禁用特殊处理）。");
        } else if (mendingBelow > 0) {
            boolean anyUsable = thresholds.stream()
                    .anyMatch(t -> t >= THRESHOLD_MIN && t <= THRESHOLD_MAX && t <= mendingBelow);
            if (!anyUsable) {
                getLogger().warning("mending.mending-only-warn-below=" + mendingBelow
                        + " 下没有任何可用的阈值档位，经验修补物品将不会收到任何警告。");
            }
        }

        // 阈值原始值校验：getIntegerList 会静默丢弃非数字项（如 "abc"），这里显式提示，避免"配置写错却毫无反应"
        List<?> rawThresholds = getConfig().getList("warnings.thresholds");
        if (rawThresholds != null) {
            for (Object raw : rawThresholds) {
                if (raw instanceof Number) continue;
                if (raw instanceof String str && isNumeric(str)) continue;
                getLogger().warning("warnings.thresholds 中的条目 \"" + raw + "\" 不是数字，将被忽略。");
            }
        }

        // 消息表校验：formats 写成 map 或结构错误时 getMapList 会返回空表，此时会退回内置默认文案
        if (!thresholds.isEmpty() && getConfig().getMapList("messages.formats").isEmpty()) {
            getLogger().warning("messages.formats 为空或结构不正确（应为列表，每项含 percent 与 message），"
                    + "将使用内置默认文案。");
        }

        // 声音配置校验：无法解析时立即提示，避免运行期静默失效
        String sound = getConfig().getString("messages.warning-sound", "");
        if (sound != null && !sound.isBlank()) {
            String name = SoundResolver.extractName(sound);
            if (name.isEmpty()) {
                getLogger().warning("messages.warning-sound=\"" + sound + "\" 缺少声音名，已忽略。");
            } else if (SoundResolver.resolve(name) == null) {
                getLogger().warning("messages.warning-sound=\"" + sound + "\" 无法在本服务端解析，将不会播放声音。"
                        + "（1.20.5~1.21.3 只支持枚举名，如 ENTITY_EXPERIENCE_ORB_PICKUP；"
                        + "1.21.4+ 也支持 entity.experience_orb.pickup 这类命名空间键）");
            }
        }

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("调试模式已开启。");
        }
    }

    /** 字符串是否为数字（含小数/负数），用于阈值配置的友好提示 */
    private static boolean isNumeric(String text) {
        try {
            Double.parseDouble(text.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
