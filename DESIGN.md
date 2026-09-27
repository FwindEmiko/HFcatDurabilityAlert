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
| 目标平台 | Paper 系服务端（Paper / Leaf / Purpur / Folia）**1.20.5 ~ 26.2** |
| JDK | 编译产物 Java 21 字节码（任何 JDK 21+ 都能构建，服务端 Java 21 / 25 均可运行） |
| 构建 | Gradle 9.7.1 (Kotlin DSL)，零第三方依赖（不需要 Shadow） |
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

当 `cooldown > 0` 时，除上述整数标记外，另用 PDC STRING `last_warn_times` 按阈值记录最近警告时间戳（格式 `50=1710000000123,30=1710000000456`），冷却期内同阈值跳过、冷却后可重复警告；修复回升到某阈值之上时清除该阈值的时间戳。

### 4.2 重置逻辑

> 实现位置提醒：`修复重置（percent > lastWarned → 清除 PDC 标记）必须放在「ignore-items 跳过」与
> 「mending 跳过」两个提前返回之前`，否则带修补的物品一旦回升到 `mending-only-warn-below` 之上就会
> 直接 return，旧标记永久残留，该物品之后再跌回低耐久时永远不会再告警。`javap -c` 可见
> `isIgnored`（偏移 292）位于 `clearWarnTimesAbove`（228）/`readLastWarned`（238）/
> `removeWarnMarker`（265）之后。

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

以 ```src/main/resources/config.yml```（默认生成文件）为准，关键项：

```yaml
warnings:
  thresholds: [50, 30, 15, 10, 5]   # 0 永远不会触发（剩余为 0 时物品已损坏）
  cooldown: -1                      # -1 每阈值一次；>0 同阈值冷却 N 秒可重复
  check-armor: true
  check-mainhand: true
  check-offhand: true
  ignore-items: []                  # 英文物品名，不区分大小写，可带 minecraft:

messages:
  formats:
    - percent: 50
      message: "&e⚠ &f{item} &e耐久度已降至 &6{percent}%&e！ &7({durability}/{max})"
    - percent: 5
      message: "&4&l!!! &f{item} &4&l马上要坏掉啦！ &7仅剩 {percent}%"
  send-chat: true
  send-actionbar: true
  send-title: false
  break-message: "&4&l!!! &f{item} &4&l已损坏！"        # 留空 = 不提示也不播放声音
  warning-sound: "ENTITY_EXPERIENCE_ORB_PICKUP:0.8:1.2" # 也可写 entity.experience_orb.pickup

mending:
  mending-only-warn-below: 5        # -1 或 0 关闭

debug: false
```

### 5.2 消息占位符

> `{item}` 会被替换为 Adventure 组件：优先自定义名（铁砧重命名），其次数据包的 `item_name` 翻译键，
> 最后用物品的默认翻译键，由玩家客户端按自身语言渲染（中文客户端显示「铁头盔」等）。

> 若某档阈值在 `messages.formats` 中没有对应条目，会使用内置兜底文案
> `&c⚠ {item} 耐久度 {percent}%！ ({durability}/{max})`；整个 `formats` 为空时加载阶段会给出警告。

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
        │   ├── HFcatDurabilityAlert.java     ← 主类（生命周期 / 配置校验 / 快照刷新）
        │   ├── DurabilityListener.java       ← 事件监听器 + 不可变配置快照 Settings
        │   ├── SoundResolver.java            ← 声音解析兼容层（枚举 / Registry 双路径）
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
   - `Sound.valueOf(String)` 在 26.2 已标记待删除，但 **1.20.5 ~ 1.21.3 只有它可用**
     （这些版本没有 `Registry.SOUND_EVENT`）；而 1.21.4+ 的注册表键是**点分名**
     （`entity.experience_orb.pickup`），用下划线名去查注册表会返回 null。
     故统一由 `SoundResolver` 运行时探测双路径并缓存结果
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

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 ~ 25 任意 | Gradle 9.7.1 可在 JDK 21/25 上运行；编译由 `options.release=21` 固定输出 Java 21 字节码 |
| Gradle | 9.7.1 (wrapper) | 项目自带 `gradlew` / `gradlew.bat`，首次运行自动下载 |
| Paper API | `1.20.6-R0.1-SNAPSHOT`(compileOnly) | 编译基线；只用 1.20.5 已存在的 API，运行期覆盖 1.20.5 ~ 26.2 |

**构建方式**：

```bash
# Linux / macOS
./gradlew build

# Windows
build.bat            # 等价于 gradlew.bat clean build
```

产物：`build/libs/HFcatDurabilityAlert-<version>.jar`（版本号来自 `build.gradle.kts`，
由 `processResources` 注入 `plugin.yml`，不会出现版本不一致）。

**为什么不再需要双 JDK**：
- 旧方案要求 Gradle 8.12 跑在 JDK 21 + toolchain 调 JDK 25，原因是 Paper 26.2 API 为 class version 69；
- 现在编译基线降到 1.20.6（class version 65），任何 JDK 21+ 编译器都可用，
  `options.release=21` 保证产物是 Java 21 字节码，从而同时兼容 Java 21（1.21.x 服务端）与 Java 25（26.x 服务端）。

**编译基线为什么是 1.20.6 而不是 1.20.5**：1.20.5 的 POM 依赖 `net.kyori:adventure-bom:4.17.0-SNAPSHOT`，
该快照已被 PaperMC 仓库清理，Gradle 无法解析；1.20.6 依赖正式版 4.17.0，可正常解析。
源码仍只用 1.20.5 就存在的 API（已用 1.20.5 的 paper-api jar 直接 `javac` 验证通过）。

### 8.3 部署

- JAR 名: `HFcatDurabilityAlert-<version>.jar`
- 部署：放入服务端 `plugins/` 目录后重启（或 `/reload confirm`）
- 首次启动自动生成 `plugins/HFcatDurabilityAlert/config.yml`

### 8.4 测试命令

```
/give @s minecraft:iron_helmet[minecraft:max_damage=407] 1
# 给一个 Stellarity 模拟物品（max_damage=407 而非铁头盔默认 165）
# 然后用战斗/耐久损耗触发警告
```

### 8.5 未来可扩展功能

- ~~Folia 支持~~：已实现——`EntityDamageEvent` 兜底路径先做 `Bukkit.isOwnedByCurrentRegion(player)`
  区域归属判断（Paper 1.20.5+ 起该方法即存在，非 Folia 恒为 true），去重表改为
  `ConcurrentHashMap` + `System.nanoTime()` 窗口，配置改为 volatile 不可变快照，
  故 `plugin.yml` 声明 `folia-supported: true`
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


---

## 十一、多版本 / 多服务端兼容适配（2026-09 公开发布前补充）

### 11.1 兼容矩阵

| 服务端 | 版本区间 | 最低 Java | 状态 |
|---|---|---|---|
| Paper | 1.20.5 ~ 26.2 | 21（26.x 需 25） | 已全量冒烟（见 README） |
| Leaf | 26.2 | 25 | 已冒烟 |
| Purpur | 1.20.6 / 1.21.8 / 26.2 | 21 / 25 | 已冒烟 |
| Folia | 26.2 | 25 | 加载 / 命令已通过（区域安全见 10.4） |
| Spigot / CraftBukkit | 任意 | — | 不支持（依赖 Paper 的 Adventure 实现） |
| Paper < 1.20.5 | 任意 | — | 不加载（`api-version: '1.20.5'` 被服务端拒绝） |

### 11.2 版本差异与适配点

| 差异 | 影响 | 适配方式 |
|---|---|---|
| `Damageable#hasMaxDamage()/getMaxDamage()` 仅 1.20.5+ 存在 | 旧服务端 `NoSuchMethodError` | `api-version='1.20.5'` + 启动时反射能力探测，缺失则自动停用并提示 |
| `Registry.SOUND_EVENT` 仅 1.21.4+ 存在；1.20.5~1.21.3 只有枚举 `Sound.valueOf`；1.21.4+ 注册表键是点分名 | 声音静默失效 | `SoundResolver` 三路兜底（注册表 → 枚举 valueOf → values() 扫描）并缓存 |
| 26.x 服务端要求 Java 25、1.21.x 要求 Java 21 | 字节码不兼容 | `options.release=21` 输出 Java 21 字节码（Java 25 可加载） |
| Folia 区域线程模型 | 跨线程访问玩家背包抛异常 | `Bukkit.isOwnedByCurrentRegion(entity)` 守卫兜底路径 + 并发容器 + volatile 快照 |
| Paper 26.1/26.2 使用年份版本号（= MC 26.1/26.2） | 版本字符串比较易错 | 不做版本号比较，只做能力探测 |

### 11.3 事件链性能

配置在 `onEnable` / `reload` 时解析为**不可变快照** `Settings`（阈值已过滤排序、
消息表已建 Map、忽略表已归一化、声音已解析、`send-*` / `check-*` 开关已缓存），
事件热路径只做一次 volatile 读 + 阈值遍历，不再每次事件解析 YAML；`debug` 日志用 `Supplier` 惰性求值。

### 11.4 Folia 安全边界

- 已保证：区域归属判断、无全局 tick 依赖、并发去重表、快照发布。
- 未覆盖：无法在测试环境模拟多区域并发伤害的真实压力场景，建议 Folia 用户自行压测后再上生产。

### 11.5 修复重置的运行时验证（2026-09-24 实测）

**背景**：第一轮审查发现一处 MAJOR —— `checkAndWarn` 里的「物品被修复 → 清除告警标记」逻辑原本放在 ignore-items 与 mending 提前返回**之后**，导致带经验修补的物品一旦回升到 `mending-only-warn-below` 之上就直接 `return`，旧标记永久残留，该物品之后再跌回低耐久时**永远不会再告警**（默认配置即受影响）。

**修复**：把修复重置（清除 PDC 标记 / 清除冷却时间戳）整体上移到两个提前返回之前。
静态验证：`javap -c` 显示 `isIgnored` 的调用点（偏移 292）位于修复重置调用（`clearWarnTimesAbove` 228 / `readLastWarned` 238 / `removeWarnMarker` 265）之后。

**运行时验证（Paper 1.21.8 实机 + mineflayer 机器人）**：

| 步骤 | 操作 | 观察到的证据 |
|---|---|---|
| 1 | 设 `mending-only-warn-below: 90`，发放 50% 耐久的经验修补剑 | `[Debug] warned for DIAMOND_SWORD threshold 50 percent 49`（正常告警并写入标记）|
| 2 | 提高同一件物品的耐久（保留 PDC），使其回到已记录阈值以上 | `[Debug] TestBot item repaired above 50%, warn marker reset` ← **修复分支确实执行并清除了标记** |
| 3 | 让物品再次掉到 50% 以下并观察是否重新告警 | ⚠ 本轮用例未复现第二次告警：修复写入会被同一 tick 的挖掘/攻击损害覆盖，物品实际未稳定回到阈值以上；「可重新触发」由标记已清除这一事实保证，尚未取得端到端日志 |

> 测试要点：装备槽（`equipment.head.components.*`）的 `data modify` 无法真正改变耐久，必须用背包槽
> （`Inventory[{Slot:0b}].components."minecraft:damage"`）；且物品持续被生物攻击/挖掘时写入会被同一 tick
> 的损害覆盖，需要在写入前暂停耐久消耗。
