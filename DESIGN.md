# HFcatDurabilityAlert 设计方案书

> 装备耐久度警告插件 · 基于 Paper 1.20.5+ 组件系统
>
> 作者: 狐魇星玖 (FCelestial) · 2026-08-25

---

## 一、项目概况

| 项 | 值 |
|---|---|
| 插件名 | HFcatDurabilityAlert |
| 包名 | `io.github.fcestial.hfcatdurabilityalert` |
| 目标平台 | Paper / Leaf 26.2+ (MC 1.21.8) |
| JDK | 25 |
| 构建 | Gradle (Kotlin DSL) + Shadow 8.3.6 |
| 外部依赖 | **零** — 仅 `paper-api` (compileOnly) |
| 参考项目 | [spommerening/ToolWarn](https://github.com/spommerening/ToolWarn) (MIT License) |

### 参考项目分析

**ToolWarn** 是一个功能相近的开源插件（MIT 协议），核心设计值得借鉴：

| 方面 | ToolWarn 做法 | HFcatDurabilityAlert 采纳情况 |
|---|---|---|
| 事件监听 | `EntityEquipmentChangedEvent` + `PlayerItemDamageEvent` 兜底 | ✅ 采纳，改为以 `PlayerItemDamageEvent` 为主，`EntityDamageEvent` 为盔甲兜底 |
| 耐久读取 | 使用 Paper API `ItemMeta.getMaxDamage()` + `Damageable.getDamage()` | ✅ 完全采纳 |
| 防重复 | 每槽位独立 tracker，物品切换时重置 | ✅ 改用 PDC 在物品上标记，更精确 |
| 阈值配置 | 无限阈值列表 | ✅ 采纳 |
| ActionBar | 支持 | ✅ 采纳 + 增加 Title 选项 |
| 热重载 | `/toolwarn reload` | ✅ 采纳 |
| 语言 | 英文消息 | 🔄 改为中文消息 |
| 修补附魔 | 无特殊处理 | ➕ 新增：有修补的物品降低警告频率 |
| 物品忽略 | 无 | ➕ 新增：可配置忽略特定物品 |

### 不采纳 ToolWarn 的部分

1. **`EntityEquipmentChangedEvent`** — 这是 Paper 26.1+ 的新事件，在 Paper 26.2 / Leaf 上可能存在兼容性差异。方案书要求以 `PlayerItemDamageEvent` 为主（事件更精确，直接对应耐久扣减）。
2. **英文消息** — 改为中文，适配 MiragEdge 服务器。
3. **1-tick delayed redundancy check** — ToolWarn 用 1 tick 延迟做冗余检查。改为直接在 `EntityDamageEvent` 中兜底检查盔甲，更直接。

---

## 二、核心技术：1.20.5+ 组件系统耐久模型

### 2.1 耐久存储方式的变革

| 维度 | 旧系统 (pre-1.20.5) | 新组件系统 (1.20.5+) |
|---|---|---|
| 已损失耐久 | NBT `Damage: short` | 组件 `minecraft:damage: int` |
| 最大耐久 | 物品类型决定（硬编码） | 组件 `minecraft:max_damage: int`（可覆盖） |
| 不可破坏 | NBT `Unbreakable: 1` | 组件 `minecraft:unbreakable` |

**关键案例：Stellarity 数据包**

Stellarity 5.5.3 的神圣套头盔基础物品是 `iron_helmet`（默认 max=165），但通过 `minecraft:max_damage` 组件覆盖为 **407**。

| 套装 | 头盔 | 胸甲 | 护腿 | 靴子 |
|---|---|---|---|---|
| 神圣 (Hallowed) | 407 | 592 | 555 | 481 |
| 冠军 (Champion) | 415 | 528 | 495 | 429 |
| 花卉 (Floral) | 407 | 592 | 555 | 429 |
| 潜影 (Shulker) | 607 | 792 | 755 | 681 |

识别 Stellarity 物品的方式：通过 `custom_data` 中的 `stellarity:item` 键（如 `"stellarity:item": "hallowed_helmet"`），而不是通过 lore 或名称。

### 2.2 正确的 API 读取方式

```java
ItemStack item = ...;
ItemMeta meta = item.getItemMeta();

if (meta instanceof Damageable damageable) {
    // ✅ 读取组件中的 max_damage（407，而非铁头盔的 165）
    // 注意：先检查 hasMaxDamage()，因为某些物品可能没有显式 max_damage 组件
    int maxDamage;
    if (meta.hasMaxDamage()) {
        maxDamage = damageable.getMaxDamage();  // 组件值（407）
    } else {
        maxDamage = item.getType().getMaxDurability(); // 回退到类型默认值
    }

    // ✅ 读取组件中的 damage（已损失，非剩余）
    int damage = damageable.getDamage();

    // ✅ 剩余耐久
    int remaining = maxDamage - damage;

    // ✅ 耐久百分比
    double percent = (double) remaining / maxDamage * 100.0;
}
```

### 2.3 必须避免的做法

```java
// ❌ 错误 1：使用基础物品类型的默认最大耐久
int maxDurability = item.getType().getMaxDurability();
// → 铁头盔返回 165，但实际 max_damage 组件是 407

// ❌ 错误 2：使用已弃用的 getDurability()
short dura = item.getDurability();
// → 已弃用且不安全

// ❌ 错误 3：直接读取旧版 NBT "Damage"
// → 组件系统下该标签不存在，返回 0 或崩

// ❌ 错误 4：把 damage 当作剩余耐久
int dmg = damageable.getDamage();
// → 这是已损失，不是剩余！
```

### 2.4 特殊场景处理

#### 不可破坏物品

```java
if (meta instanceof Damageable damageable && damageable.hasDamage()) {
    // 有耐久
} else {
    // 不可破坏或无耐久的物品，跳过
}

// 补充：某些 Paper 版本 getMaxDamage() 对不可破坏物品返回 -1
if (maxDamage <= 0) {
    return; // 不可破坏或无耐久，跳过
}
```

#### 无耐久物品

```java
if (!(meta instanceof Damageable) || !damageable.hasDamage()) {
    return; // 剑、工具、盔甲以外的物品
}
```

#### 物品堆栈大小 > 1

```java
if (item.getAmount() > 1) {
    return; // 堆叠物品不触发耐久警告
}
```

#### Mending（经验修补）附魔

```java
int mendingLevel = item.getEnchantmentLevel(Enchantment.MENDING);
if (mendingLevel > 0 && percent < mendingOnlyBelow) {
    // 有修补的物品在低耐久时才警告
}
```

#### Unbreaking（耐久）附魔

不影响组件中的 `damage` 值，不影响读取逻辑，但影响实际寿命。可在未来版本考虑根据 Unbreaking 等级降低警告阈值。

---

## 三、事件监听策略

### 3.1 主事件：PlayerItemDamageEvent

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onItemDamage(PlayerItemDamageEvent event) {
    // 这个事件精确地告诉你哪个物品被扣了耐久
    // 避免每 tick 扫描
}
```

**优点**：
- 精确到单次耐久扣减
- 直接获得受检物品
- 无需轮询，性能最优

**限制**：
- 某些 Paper 版本在盔甲耐久扣减时不触发此事件

### 3.2 兜底事件：EntityDamageEvent

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onPlayerDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    // 检查所有盔甲槽位
    for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    }) {
        ItemStack armor = player.getInventory().getItem(slot);
        if (armor == null || armor.getType() == Material.AIR) continue;
        checkAndWarn(player, armor, slot, config);
    }
}
```

**作用**：当玩家受伤时，盔甲耐久可能扣减。某些 Paper 版本不为此触发 `PlayerItemDamageEvent`，所以需要兜底。

### 3.3 最终事件：PlayerItemBreakEvent

```java
@EventHandler(priority = EventPriority.MONITOR)
public void onItemBreak(PlayerItemBreakEvent event) {
    // 物品完全损坏时，发送最终警告
}
```

### 3.4 事件优先级

所有监听器使用 `EventPriority.MONITOR` + `ignoreCancelled = true`：
- `MONITOR` 表示只读取，不干预其他插件
- `ignoreCancelled = true` 确保其他插件取消的事件不会触发警告

---

## 四、防重复警告机制

### 4.1 PDC 标记法

使用 `PersistentDataContainer` 在物品上记录上次警告的阈值：

```java
private static final NamespacedKey LAST_WARN_KEY =
    new NamespacedKey("hfcatdurabilityalert", "last_warn_threshold");

// 检查是否已警告过
private boolean hasWarnedForThreshold(ItemStack item, int threshold) {
    ItemMeta meta = item.getItemMeta();
    var pdc = meta.getPersistentDataContainer();
    if (!pdc.has(lastWarnKey, PersistentDataType.INTEGER)) return false;
    int lastWarned = pdc.getOrDefault(lastWarnKey, PersistentDataType.INTEGER, -1);
    // 级联语义：>= 上次标记阈值的档位视为已警告（已警告 50 后，30 视为未警告，可继续触发）
    return threshold >= lastWarned;
}

// 标记已警告
private void markWarnedThreshold(ItemStack item, int threshold) {
    item.editMeta(meta -> {
        meta.getPersistentDataContainer().set(lastWarnKey, PersistentDataType.INTEGER, threshold);
    });
}
```

### 4.2 重置逻辑

当物品被修复（耐久回升）时，需要重置标记：

- **方案A（当前实现）**：检查时如果当前百分比 > 上次记录的阈值（`last_warn_threshold`），说明物品被修复了，清除 PDC 标记
- **方案B（备选）**：每次耐久回升到更高阈值时自动重置

**行为说明**：重置标记后，同一事件可能立即以「最高 <= 当前百分比的档位」重新触发警告（例如 30% 修复到 45% 会重新触发 50% 档），这是方案A 的既定语义：修复后允许各阈值重新触发。

### 4.3 配置选项

```yaml
# 重复警告冷却（秒），-1 表示每个阈值只警告一次
# >0 表示同一阈值冷却 N 秒后可重复警告（无需修复）
cooldown: -1
```

`-1` = 每个阈值只警告一次（PDC 标记法）
`> 0` = 冷却时间后可重复警告（PDC 按阈值存储最近警告时间戳）
`0` 或小于 `-1` = 无效值，按 `-1` 处理并告警

---

## 五、配置项设计

### 5.1 完整 config.yml

```yaml
warnings:
  thresholds: [50, 30, 15, 10, 5]
  cooldown: -1
  check-armor: true
  check-mainhand: true
  check-offhand: true
  ignore-items: ["elytra", "shield"]

messages:
  formats:
    - percent: 50
      message: "&e⚠ &f{item} &e耐久度已降至 &6{percent}%&e！"
    - percent: 5
      message: "&4&l!!! &f{item} &4&l马上要断了！"
  send-chat: true
  send-actionbar: true
  send-title: false
  warning-sound: "ENTITY_EXPERIENCE_ORB_PICKUP:0.8:1.2"

mending:
  mending-only-warn-below: 10

debug: false
```

### 5.2 消息占位符

| 占位符 | 说明 | 示例 |
|---|---|---|
| `{item}` | 物品名称（自定义名优先，否则用类型名） | `Iron Helmet` |
| `{slot}` | 装备槽位中文名 | `头盔` / `主手` |
| `{durability}` | 剩余耐久点数 | `252` |
| `{max}` | 最大耐久点数 | `407` |
| `{percent}` | 剩余百分比（整数） | `10` |
| `{threshold}` | 触发的阈值百分比 | `10` |

颜色代码用 `&` 前缀（`&a`, `&c`, `&l` 等）。

---

## 六、完整流程图

```
PlayerItemDamageEvent 触发
  ↓
权限检查 (hfcatdurabilityalert.use)
  ↓
槽位检查 (armor/mainhand/offhand)
  ↓
物品数量 > 1？ → 跳过
  ↓
meta instanceof Damageable？ → 否 → 跳过
  ↓
damageable.hasDamage()？ → 否 → 跳过
  ↓
maxDamage = hasMaxDamage() ? getMaxDamage() : type.getMaxDurability()
  ↓
maxDamage <= 0？ → 是 → 跳过（不可破坏）
  ↓
remaining = maxDamage - damageable.getDamage()
percent = remaining / maxDamage * 100
  ↓
在忽略列表中？ → 是 → 跳过
  ↓
有经验修补 && percent > mending-only-warn-below？ → 跳过
  ↓
遍历阈值（从高到低）
  ↓
percent <= 阈值？
  → 否 → 检查下一个阈值
  → 是 → 已对此阈值警告过？
           → 是 → 检查下一个更低阈值（级联）
           → 否 → 发送警告消息（Chat/ActionBar/Title）
                  播放声音
                  记录阈值到 PDC
                  结束
```

---

## 七、项目文件结构

```
HFcatDurabilityAlert/
├── build.gradle.kts              ← Gradle 构建文件
├── settings.gradle.kts           ← 项目设置
├── .gitignore
├── README.md
├── DESIGN.md                     ← 本文件（设计方案书）
└── src/
    └── main/
        ├── java/io/github/fcestial/hfcatdurabilityalert/
        │   ├── HFcatDurabilityAlert.java     ← 主类
        │   ├── DurabilityListener.java       ← 事件监听器
        │   └── AlertCommand.java              ← 命令处理
        └── resources/
            ├── plugin.yml                     ← 插件描述
            └── config.yml                    ← 默认配置
```

---

## 八、实施注意事项

### 8.1 对实施 Agent 的要求

1. **耐久读取铁则**：
   - 必须使用 `Damageable.getDamage()` + `ItemMeta.getMaxDamage()`
   - 绝对不使用 `item.getType().getMaxDurability()` 作为唯一来源
   - 绝对不使用 `item.getDurability()`（已弃用）
   - 必须检查 `hasMaxDamage()` 后再调用 `getMaxDamage()`

2. **API 坑点（实测编译验证）**：
   - `PlayerItemDamageEvent` **没有** `getSlot()` 方法！槽位判断必须通过匹配玩家实时装备（`findSlot()` 方法，比较主手/副手/盔甲各槽位的 `getItemInMainHand()`/`getItem(slot)` 与事件物品）
   - `hasMaxDamage()`/`getMaxDamage()` 定义在 `Damageable` 接口上，不在 `ItemMeta` 上——必须先 `meta instanceof Damageable` 强转后再调用
   - `Sound.valueOf(String)` 在 26.2 已标记待删除（编译警告 removal）——改用 `Registry.SOUND_EVENT.get(NamespacedKey.minecraft(name.toLowerCase()))`
   - `Enchantment.MENDING` 获取等级用 `item.getEnchantmentLevel(Enchantment.MENDING)`，在 26.2 仍可用

3. **事件监听铁则**：
   - `PlayerItemDamageEvent` 为主监听器
   - `EntityDamageEvent` 为盔甲兜底
   - `PlayerItemBreakEvent` 为最终警告
   - 全部使用 `MONITOR` 优先级 + `ignoreCancelled = true`

4. **防重复机制**：
   - 使用 PDC 在物品上记录 `last_warn_threshold`
   - 每个阈值只警告一次
   - 物品被修复后允许重新触发

5. **配置可扩展性**：
   - 阈值列表可无限扩展
   - 每个阈值可有独立消息格式
   - 支持中文槽位名称

6. **权限节点**：
   - `hfcatdurabilityalert.admin` — 管理命令（默认 op）
   - `hfcatdurabilityalert.use` — 接收警告（默认 true）

### 8.2 构建环境

| 工具 | 版本 | 路径 |
|---|---|---|
| JDK 25（编译用） | Azul Zulu 25.0.3 | `F:\env\jdk\azul-25.0.3` |
| JDK 21（Gradle 启动用） | Azul Zulu 21.0.11 | `F:\env\jdk\azul-21.0.11` |
| Gradle | 8.12 (wrapper) | 项目自带 `gradlew.bat` |
| Paper API | 26.2.build.+ | Maven 仓库自动解析 |

**构建方式（重要）**：

```bash
# Windows：直接双击 build.bat 或执行
F:\Java_project\HFcatDurabilityAlert\build.bat

# 或手动（必须先设 JAVA_HOME=JDK21，Gradle 8.12 不支持 JDK 25 运行时）
set JAVA_HOME=F:\env\jdk\azul-21.0.11
gradlew.bat shadowJar
```

**为什么需要两个 JDK**：
- Gradle 8.12 在 JDK 25 上启动会直接失败（`25.0.3` 无意义报错）
- Paper 26.2 API 编译为 class version 69.0，编译器必须用 JDK 25
- 解决方案：Gradle 跑在 JDK 21 上，通过 `gradle.properties` 中的 `org.gradle.java.installations.paths` 声明 JDK 25，由 toolchain 自动调用

### 8.3 部署

- JAR 名: `HFcatDurabilityAlert-1.0.0.jar`
- 部署路径: `M:\MainServer\plugins\` (或用户手动部署)
- 首次启动自动生成 `plugins/HFcatDurabilityAlert/config.yml`

### 8.4 测试命令

```
/give @s minecraft:iron_helmet[minecraft:max_damage=407] 1
# 给一个 Stellarity 模拟物品（max_damage=407 而非铁头盔默认 165）
# 然后用战斗/耐久损耗触发警告
```

### 8.5 未来可扩展功能

- **Folia 支持**：使用 `Plugin.getRegionScheduler()` 替代直接调用
- **MiniMessage 格式**：将 `&` 颜色代码替换为 MiniMessage 标签
- **Unbreaking 智能阈值**：根据耐久附魔等级动态调整警告阈值
- **PlaceholderAPI 集成**：将耐久信息暴露给其他插件
- **CraftEngine 物品识别**：通过 CE 的 custom_data 识别自定义物品类型

---

## 九、API 参考

### Paper API Javadocs

- [Damageable](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/inventory/meta/Damageable.html) — `getDamage()`, `getMaxDamage()`, `hasDamage()`, `hasMaxDamage()`
- [ItemMeta](https://jd.papermc.io/paper/org/bukkit/inventory/meta/ItemMeta.html) — 继承的方法
- [ItemStack](https://jd.papermc.io/paper/org/bukkit/inventory/ItemStack.html) — `getDurability()` 已弃用
- [Data Components](https://docs.papermc.io/paper/dev/data-component-api) — 组件系统文档

### 关键 API 签名

```
Damageable (extends ItemMeta):
    int getDamage()              // 已损失耐久
    int getMaxDamage()           // 组件 max_damage（需先 hasMaxDamage()）
    boolean hasDamage()          // 是否有耐久损伤
    boolean hasMaxDamage()       // 是否有 max_damage 组件
    void setDamage(int)          // 设置损伤
    void setMaxDamage(Integer)    // 设置最大耐久
```

---

## 十、参考开源项目对比

| 特性 | ToolWarn (MIT) | HFcatDurabilityAlert |
|---|---|---|
| 事件 | EntityEquipmentChangedEvent + PlayerItemDamageEvent | PlayerItemDamageEvent + EntityDamageEvent + PlayerItemBreakEvent |
| 阈值 | 无限 | 无限 |
| 消息语言 | 英文 | 中文 |
| 防重复 | 每槽位 tracker | PDC 物品级标记 |
| 修补支持 | 无 | 有（mending-only-warn-below） |
| 物品忽略 | 无 | 有（ignore-items） |
| ActionBar | 有 | 有 |
| Title | 无 | 有（可选） |
| 热重载 | 有 | 有 |
| 外部依赖 | 零 | 零 |

---

*© 2026 狐魇星玖 (FCelestial) · MiragEdge*
