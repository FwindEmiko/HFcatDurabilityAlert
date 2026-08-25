# HFcatDurabilityAlert

> **装备耐久度警告插件** — 基于 Paper 1.20.5+ 组件系统的精确耐久监控
>
> 作者: 狐魇星玖 (FCelestial) · MiragEdge

## 项目信息

| 项 | 值 |
|---|---|
| 包名 | `io.github.fcestial.hfcatdurabilityalert` |
| 目标平台 | Paper / Leaf 26.2+ (MC 1.21.8) |
| JDK | 编译 JDK 25（Paper API class version 69）、Gradle 运行 JDK 21 |
| 构建 | Gradle 8.12 (Kotlin DSL) + Shadow 8.3.6 |
| 依赖 | **零外部依赖** — 仅 `paper-api` (compileOnly) |
| 设计文档 | [DESIGN.md](DESIGN.md) |

## 功能

- 基于 **组件系统**（`minecraft:damage` / `minecraft:max_damage`）精确读取耐久，兼容 Stellarity 等自定义 `max_damage` 物品
- 阈值列表无限扩展，每个阈值可配置独立消息（`{item}` `{slot}` `{durability}` `{max}` `{percent}` `{threshold}` 占位符）
- 三种输出通道：聊天 / 动作栏 / Title，可独立开关；可配置警告音效（`SOUND:音量:音调`）
- **PDC 防重复**：每个阈值只警告一次，物品修复后可重新触发
- 经验修补物品低耐久才警告（`mending-only-warn-below`）
- 可忽略指定物品（如 elytra、shield）
- 热重载：`/hfcatdurabilityalert reload`

## 命令与权限

| 命令 | 说明 |
|---|---|
| `/hfcatdurabilityalert reload` | 重载配置（热重载） |
| `/hfcatdurabilityalert status` | 查看当前配置状态 |
| `/hfcatdurabilityalert help` | 显示帮助 |

别名：`/hdura`、`/hdalert`

| 权限 | 默认 | 说明 |
|---|---|---|
| `hfcatdurabilityalert.admin` | op | 管理命令 |
| `hfcatdurabilityalert.use` | true | 接收耐久警告 |

## 构建

### Windows（开发机，双 JDK）

直接双击 `build.bat`，或手动（确保 `gradle.properties` 中的 JDK 路径正确）：

```bat
set JAVA_HOME=F:\\env\\jdk\\azul-21.0.11
gradlew.bat shadowJar
```

### Linux

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # Gradle 8.12 运行用
./gradlew shadowJar -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-25-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64
```

> **双 JDK 原因**：Gradle 8.12 不能在 JDK 25 上启动；Paper 26.2 API 编译为 class version 69（需 JDK 25 编译器）。
> JDK 路径在不同平台下不能混写在同一个 `gradle.properties` 中（Windows 的 `F:/` 路径在 Linux 上会被解析为 URL，Linux 的 `/usr/lib` 路径在 Windows 上会被解析为相对目录），
> 因此 `gradle.properties` 默认存放 Windows 路径；Linux 用户需通过 `-D` 参数传入本机路径，或修改 `gradle.properties`。## 测试

```text
/give @s minecraft:iron_helmet[minecraft:max_damage=407] 1
# 模拟 Stellarity 物品（max_damage=407 而非铁头盔默认 165），损耗耐久触发警告
```

## 参考

- 设计方案书: [DESIGN.md](DESIGN.md)
- 参考开源项目: [spommerening/ToolWarn](https://github.com/spommerening/ToolWarn) (MIT)
- Paper API 文档: [docs.papermc.io](https://docs.papermc.io/paper/dev/data-component-api)
