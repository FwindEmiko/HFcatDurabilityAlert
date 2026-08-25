package io.github.fcestial.hfcatdurabilityalert;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * HFcatDurabilityAlert 插件主类
 *
 * 装备耐久度警告插件，基于 Paper 1.20.5+ 组件系统精确读取耐久。
 *
 * 核心原理：
 * - 旧版 NBT Damage: short 已弃用，1.20.5+ 改用 minecraft:damage / minecraft:max_damage 组件
 * - Paper API 的 Damageable.getDamage() 返回已损失耐久（非剩余）
 * - Damageable.getMaxDamage() 自动读取 max_damage 组件（如 Stellarity 将铁头盔覆盖为 407）
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateConfig();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new DurabilityListener(this), this);

        // 注册命令（executor 与 tabCompleter 复用同一无状态实例）
        var cmd = getCommand("hfcatdurabilityalert");
        if (cmd != null) {
            var handler = new AlertCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        int thresholds = getConfig().getIntegerList("warnings.thresholds").size();
        getLogger().info("HFcatDurabilityAlert 已启用！共 " + thresholds + " 个警告阈值，监控装备耐久中...");
    }

    @Override
    public void onDisable() {
        getLogger().info("HFcatDurabilityAlert 已禁用。");
    }

    /** 重载配置并做合法性校验 */
    public void reload() {
        reloadConfig();
        validateConfig();
        getLogger().info("配置已重载。");
    }

    /**
     * 配置合法性校验（只告警，不修改文件）：
     * - 阈值必须在 0~100 之间
     * - cooldown 目前仅支持 -1（每个阈值只警告一次），正数会在未来版本实现
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
            }
        }

        int cooldown = getConfig().getInt("warnings.cooldown", -1);
        if (cooldown > 0) {
            getLogger().warning("warnings.cooldown=" + cooldown + "：按冷却时间重复警告的功能尚未实现，"
                    + "当前行为为每个阈值只警告一次（与 cooldown=-1 相同）。");
        } else if (cooldown == 0 || cooldown < -1) {
            getLogger().warning("warnings.cooldown=" + cooldown + " 无效（仅支持 -1），将按 -1 处理"
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

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("调试模式已开启。");
        }
    }
}
