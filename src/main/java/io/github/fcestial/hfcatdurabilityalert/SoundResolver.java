package io.github.fcestial.hfcatdurabilityalert;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 声音解析兼容层（Paper 1.20.5 ~ 26.2）。
 *
 * 版本差异：
 * - 1.20.5 ~ 1.21.3：Sound 是枚举，只能用 Sound.valueOf("ENTITY_EXPERIENCE_ORB_PICKUP")
 * - 1.21.4+：Sound 是接口 + Registry.SOUND_EVENT，注册表键是<b>点分名</b>
 *   （minecraft:entity.experience_orb.pickup），此时
 *   Registry.SOUND_EVENT.get(minecraft:entity_experience_orb_pickup) 返回 null，
 *   必须用点分键查询。
 *
 * 因此本类在运行时探测可用的解析方式（注册表优先，枚举兜底），并按名字缓存结果：
 * 配置既可以写老式枚举名（ENTITY_EXPERIENCE_ORB_PICKUP），也可以写命名空间键
 * （entity.experience_orb.pickup / minecraft:entity.experience_orb.pickup）。
 *
 * 全部通过反射访问 Sound.valueOf / Sound.values / Registry.SOUND_EVENT，
 * 以便同一份字节码在 1.20.5（无 SOUND_EVENT 字段）与 26.2（有）上都能运行。
 */
public final class SoundResolver {

    /** 名字（小写） → 解析结果；null 值表示该名字在本服务端无法解析 */
    private static final Map<String, Sound> CACHE = new HashMap<>();

    private static final Object SOUND_REGISTRY;
    private static final Method REGISTRY_GET;
    private static final Method VALUE_OF;
    private static final Method VALUES;

    static {
        Object registry = null;
        Method get = null;
        try {
            Field field = Registry.class.getField("SOUND_EVENT");
            registry = field.get(null);
            get = Registry.class.getMethod("get", NamespacedKey.class);
        } catch (Throwable ignored) {
            registry = null;
            get = null;
        }
        SOUND_REGISTRY = registry;
        REGISTRY_GET = get;
        VALUE_OF = findMethod(Sound.class, "valueOf", String.class);
        VALUES = findMethod(Sound.class, "values");
    }

    private SoundResolver() {
    }

    /** 注册表可用（Paper 1.21.4+） */
    public static boolean registryAvailable() {
        return SOUND_REGISTRY != null && REGISTRY_GET != null;
    }

    /** 供 /status 展示的解析方式描述 */
    public static String mode() {
        return registryAvailable()
                ? "Registry.SOUND_EVENT + 枚举兜底"
                : "枚举 Sound.valueOf（老版服务端）";
    }

    /**
     * 解析声音名。
     *
     * @param raw 配置中的值：枚举名 / 点分键 / minecraft: 前缀键（大小写不敏感）
     * @return 解析到的 Sound；无法解析时返回 null（调用方负责提示）
     */
    public static synchronized Sound resolve(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()) return null;
        if (name.regionMatches(true, 0, "minecraft:", 0, 10)) {
            name = name.substring(10);
        }
        String cacheKey = name.toLowerCase(Locale.ROOT);
        if (CACHE.containsKey(cacheKey)) {
            return CACHE.get(cacheKey);
        }

        Sound sound = null;
        if (isNamespacedKey(name)) {
            sound = byKey(name);
        }
        if (sound == null) {
            sound = byValueOf(name);
        }
        if (sound == null) {
            sound = byKey(name);
        }
        CACHE.put(cacheKey, sound);
        return sound;
    }

    /** 从 "声音名[:音量[:音调]]" 配置值中提取声音名（兼容 minecraft: 前缀） */
    public static String extractName(String configValue) {
        String[] parts = configValue.split(":", -1);
        if (parts.length >= 2 && parts[0].equalsIgnoreCase("minecraft")) {
            return parts[0] + ":" + parts[1];
        }
        return parts.length > 0 ? parts[0] : "";
    }

    /** 音量/音调参数在 "声音名[:音量[:音调]]" 中的起始下标 */
    public static int paramStart(String configValue) {
        String[] parts = configValue.split(":", -1);
        return (parts.length >= 2 && parts[0].equalsIgnoreCase("minecraft")) ? 2 : 1;
    }

    /** 是否是点分/带冒号的命名空间键（而非老式枚举名） */
    private static boolean isNamespacedKey(String name) {
        return name.indexOf('.') >= 0 || name.indexOf(':') >= 0;
    }

    /** 按注册表键查询：优先注册表，老版服务端退化为遍历枚举比对 key */
    private static Sound byKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (registryAvailable()) {
            try {
                Object value = REGISTRY_GET.invoke(SOUND_REGISTRY, NamespacedKey.minecraft(lower));
                if (value instanceof Sound sound) return sound;
            } catch (Throwable ignored) {
                // 交给枚举兜底
            }
        }
        Sound[] all = allSounds();
        if (all != null) {
            for (Sound sound : all) {
                try {
                    NamespacedKey soundKey = sound.getKey();
                    if (soundKey != null && soundKey.getKey().equalsIgnoreCase(lower)) {
                        return sound;
                    }
                } catch (Throwable ignored) {
                    // 忽略单个条目的异常
                }
            }
        }
        return null;
    }

    /** 老式枚举名解析（1.20.5 ~ 26.2 均可用） */
    private static Sound byValueOf(String name) {
        if (VALUE_OF == null) return null;
        try {
            Object value = VALUE_OF.invoke(null, name.toUpperCase(Locale.ROOT));
            return value instanceof Sound sound ? sound : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Sound[] allSounds() {
        if (VALUES == null) return null;
        try {
            Object value = VALUES.invoke(null);
            return value instanceof Sound[] sounds ? sounds : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
