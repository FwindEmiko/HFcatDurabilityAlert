package io.github.fcestial.hfcatdurabilityalert;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 命令处理器
 *
 * /hfcatdurabilityalert reload  — 重载配置
 * /hfcatdurabilityalert status  — 查看状态
 * 别名: /hdura, /hdalert
 *
 * 输出统一走 Adventure（LegacyComponentSerializer 显式 § 序列化器），
 * 不使用已弃用的 sendMessage(String)。
 */
public class AlertCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final HFcatDurabilityAlert plugin;

    public AlertCommand(HFcatDurabilityAlert plugin) {
        this.plugin = plugin;
    }

    /** 发送 § 颜色代码文本（内部转 Adventure Component） */
    private static void send(CommandSender sender, String legacyText) {
        sender.sendMessage(LEGACY.deserialize(legacyText));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(HFcatDurabilityAlert.PERMISSION_ADMIN)) {
            send(sender, "§c你没有权限执行此命令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                send(sender, "§a配置已重载！");
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }
            default -> {
                send(sender, "§c未知子命令。用法: /" + label + " [reload|status|help]");
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        send(sender, "§e=== HFcatDurabilityAlert ===");
        send(sender, "§7/" + label + " reload §8- 重载配置");
        send(sender, "§7/" + label + " status §8- 查看状态");
        send(sender, "§7/" + label + " help §8- 显示帮助");
    }

    private void sendStatus(CommandSender sender) {
        var config = plugin.getConfig();
        send(sender, "§e=== HFcatDurabilityAlert 状态 ===");
        send(sender, "§7版本: §f" + plugin.getPluginMeta().getVersion());
        send(sender, "§7服务端: §f" + Bukkit.getName() + " " + Bukkit.getBukkitVersion()
                + " §8(MC " + Bukkit.getMinecraftVersion() + ", Java " + System.getProperty("java.version") + ")");
        send(sender, "§7声音解析: §f" + SoundResolver.mode());
        send(sender, "§7阈值: §f" + config.getIntegerList("warnings.thresholds"));
        int cooldown = config.getInt("warnings.cooldown", -1);
        String cooldownDesc;
        if (cooldown < 0) {
            cooldownDesc = "每阈值一次";
        } else if (cooldown > 0) {
            cooldownDesc = cooldown + " 秒后可重复";
        } else {
            cooldownDesc = "无效（按每阈值一次）";
        }
        send(sender, "§7冷却: §f" + cooldown + " §8(" + cooldownDesc + ")");
        send(sender, "§7检查盔甲: §f" + config.getBoolean("warnings.check-armor", true)
                + " §7主手: §f" + config.getBoolean("warnings.check-mainhand", true)
                + " §7副手: §f" + config.getBoolean("warnings.check-offhand", true));
        send(sender, "§7忽略物品: §f" + config.getStringList("warnings.ignore-items"));
        send(sender, "§7聊天: §f" + config.getBoolean("messages.send-chat", true)
                + " §7动作栏: §f" + config.getBoolean("messages.send-actionbar", true)
                + " §7Title: §f" + config.getBoolean("messages.send-title", false));
        send(sender, "§7声音: §f" + (config.getString("messages.warning-sound", "").isEmpty()
                ? "关闭" : config.getString("messages.warning-sound")));
        send(sender, "§7修补阈值: §f" + config.getInt("mending.mending-only-warn-below", -1));
        send(sender, "§7调试: §f" + config.getBoolean("debug", false));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission(HFcatDurabilityAlert.PERMISSION_ADMIN)) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : new String[]{"reload", "status", "help"}) {
                if (sub.startsWith(prefix)) completions.add(sub);
            }
        }
        return completions;
    }
}
