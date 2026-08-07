# MieRS MieBot 插件

MieRS 是用于 MieBot 的原生 Kotlin/JVM 21 插件，基于 MieBot Plugin API/SPI
1.0.6。每个成功进入队列的 `/miers` 查询都会实时请求 CodexRadar，计算并生成
21 个模型档位的 IQ 图片，再通过 MieBot 媒体 Outbox 回复触发消息。

## 指令

| 指令 | 作用 |
| --- | --- |
| `/miers` | 实时抓取 CodexRadar 数据，生成并发送模型 IQ 图片。配置中的禁用群、队列上限和用户冷却只作用于此指令。 |
| `/miers help` | 显示插件全部指令及作用；即使当前群禁用了 IQ 查询也可用。 |
| `/miers st` | 切换当前群是否禁用 `/miers`。仅群管理员和群主可用，结果实时写回 `config.yml`。 |

## 构建

需要 JDK 21 和 Gradle。默认使用以下 MieBot 本地 SDK 仓库：

```text
D:/开发文档/miebot/build/plugin-sdk/repository
```

使用默认 SDK 路径构建：

```powershell
gradle clean jar --no-daemon
```

也可以通过 `qqbotSdkRepository` 覆盖 SDK 路径：

```powershell
gradle -PqqbotSdkRepository="D:/path/to/plugin-sdk/repository" clean jar --no-daemon
```

MieBot API/SPI 只用于编译，不会打入插件 JAR。插件私有嵌入 Gson 2.13.1
用于流式解析网站 JSON，并嵌入 SnakeYAML 2.2 用于配置读写。

## 实时数据

`/miers` 每次实际执行时请求：

```text
https://codexradar.com/api/intelligence-efficiency?refresh=1
```

插件使用 CodexRadar 页面相同的公式 `通过题数 / 有效题数 * 150` 计算 IQ。
请求超时为 20 秒，响应体最多 8 MiB，并且必须恰好包含受支持的 21 个模型档位。
抓取、解析或图片生成失败时会回复失败提示，不会回退到内置旧数据或发送旧图片。

## 配置

绑定配置文件为 `config.yml`：

| 字段 | 类型与约束 | 作用 |
| --- | --- | --- |
| `blockedGroups` | 不含空白的群 ID 数组 | 禁用 `/miers` 查询的群，默认 `[]`；通常由 `/miers st` 维护。 |
| `maxQueue` | 正整数 | 最大已接收 IQ 请求数，包含正在处理和等待中的任务，默认 `10`。 |
| `queueFullMessage` | 最多 4000 字符的字符串 | 队列满时的提示；空字符串表示不发送提示。 |
| `cooldownMinutes` | 非负整数 | 每个用户两次成功入队之间的分钟数；`0` 表示不限制。队列拒绝不会消耗冷却。 |

## 图片预览脚本

项目根目录保留了可重复使用的 `render-codexradar-iq.ps1`：

```powershell
.\render-codexradar-iq.ps1
.\render-codexradar-iq.ps1 .\output\miers-iq.png
```

不传参数时输出 `codexradar-iq-seven-by-three.png`。插件部署后不依赖
PowerShell，而是使用 Java 21 的 `BufferedImage` 在进程内生成图片。预览脚本本身
也会实时请求上述 CodexRadar 接口，因此运行时需要可访问该网站。

## 制品

可部署 JAR 输出到：

```text
build/libs/miers-<version>.jar
```

JAR 包含插件 Manifest、ServiceLoader 注册、`config.yml`、
`qqbot-plugin-schema.json`、Gson 和 SnakeYAML，不包含 MieBot API/SPI。
