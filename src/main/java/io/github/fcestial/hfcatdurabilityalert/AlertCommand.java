package io.github.fcestial.hfcatdurabilityalert;

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
 */
public class AlertCommand implements CommandExecutor, TabCompleter {

    private final HFcatDurabilityAlert plugin;

    public AlertCommand(HFcatDurabilityAlert plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(HFcatDurabilityAlert.PERMISSION_ADMIN)) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage("§a配置已重载！");
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
                sender.sendMessage("§c未知子命令。用法: /" + label + " [reload|status|help]");
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§e=== HFcatDurabilityAlert ===");
        sender.sendMessage("§7/" + label + " reload §8- 重载配置");
        sender.sendMessage("§7/" + label + " status §8- 查看状态");
        sender.sendMessage("§7/" + label + " help §8- 显示帮助");
    }

    private void sendStatus(CommandSender sender) {
        var config = plugin.getConfig();
        sender.sendMessage("§e=== HFcatDurabilityAlert 状态 ===");
        sender.sendMessage("§7版本: §f" + plugin.getPluginMeta().getVersion());
        sender.sendMessage("§7阈值: §f" + config.getIntegerList("warnings.thresholds"));
        int cooldown = config.getInt("warnings.cooldown", -1);
        sender.sendMessage("§7冷却: §f" + cooldown
                + " §8(" + (cooldown < 0 ? "每阈值一次" : "未实现，按一次处理") + ")");
        sender.sendMessage("§7检查盔甲: §f" + config.getBoolean("warnings.check-armor", true)
                + " §7主手: §f" + config.getBoolean("warnings.check-mainhand", true)
                + " §7副手: §f" + config.getBoolean("warnings.check-offhand", true));
        sender.sendMessage("§7忽略物品: §f" + config.getStringList("warnings.ignore-items"));
        sender.sendMessage("§7聊天: §f" + config.getBoolean("messages.send-chat", true)
                + " §7动作栏: §f" + config.getBoolean("messages.send-actionbar", true)
                + " §7Title: §f" + config.getBoolean("messages.send-title", false));
        sender.sendMessage("§7声音: §f" + (config.getString("messages.warning-sound", "").isEmpty()
                ? "关闭" : config.getString("messages.warning-sound")));
        sender.sendMessage("§7修补阈值: §f" + config.getInt("mending.mending-only-warn-below", -1));
        sender.sendMessage("§7调试: §f" + config.getBoolean("debug", false));
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
