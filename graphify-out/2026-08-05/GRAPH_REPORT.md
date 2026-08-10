# Graph Report - AIRS  (2026-08-05)

## Corpus Check
- 15 files · ~16,777 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 200 nodes · 363 edges · 14 communities (12 shown, 2 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 15 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- MiersConfiguration.kt
- MiersPlugin
- qqbot-plugin-schema.json
- MiersIqImageRenderer
- MiersConfiguration
- MiersRequestQueue
- MiersCooldownTracker
- MiersModelFamily
- MieRS MieBot 插件
- MiersIqImageRendererTest
- MiersConfigurationTest

## God Nodes (most connected - your core abstractions)
1. `MiersConfiguration` - 20 edges
2. `MiersIqImageRenderer` - 17 edges
3. `MiersPlugin` - 15 edges
4. `MiersRequestQueue` - 15 edges
5. `MiersPluginFactoryTest` - 14 edges
6. `MiersConfigurationStore` - 11 edges
7. `MiersCooldownTracker` - 10 edges
8. `validateGroupId()` - 7 edges
9. `MiersModelFamily` - 7 edges
10. `MiersCooldownTrackerTest` - 7 edges

## Surprising Connections (you probably didn't know these)
- `defaults()` --references--> `MiersConfiguration`  [EXTRACTED]
  src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersConfiguration.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersConfiguration.kt  _Bridges community 4 → community 0_

## Import Cycles
- None detected.

## Communities (14 total, 2 thin omitted)

### Community 0 - "MiersConfiguration.kt"
Cohesion: 0.12
Nodes (16): IllegalArgumentException, defaults(), immutableSortedSet(), load(), MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, open() (+8 more)

### Community 1 - "MiersPlugin"
Cohesion: 0.14
Nodes (14): BotPlugin, BotPluginFactory, EventSubscription, InboundMessage, PluginRuntimeContext, PluginEvent, MiersCommand, HELP (+6 more)

### Community 2 - "qqbot-plugin-schema.json"
Cohesion: 0.08
Nodes (25): blockedGroups, cooldownMinutes, maxQueue, queueFullMessage, additionalProperties, items, type, minimum (+17 more)

### Community 3 - "MiersIqImageRenderer"
Cohesion: 0.27
Nodes (7): Color, Font, FontMetrics, Graphics2D, color(), ByteArray, MiersIqImageRenderer

### Community 4 - "MiersConfiguration"
Cohesion: 0.24
Nodes (8): GroupMemberRole, MessageTargetType, PluginTestContext, MiersConfiguration, ByteArray, PluginEvent, MiersPluginFactoryTest, sampleImage()

### Community 5 - "MiersRequestQueue"
Cohesion: 0.20
Nodes (5): AutoCloseable, ActiveTask, MiersRequestQueue, QueuedTask, MiersRequestQueueTest

### Community 6 - "MiersCooldownTracker"
Cohesion: 0.19
Nodes (6): Accepted, Limited, MiersCooldownDecision, MiersCooldownReservation, MiersCooldownTracker, MiersCooldownTrackerTest

### Community 7 - "MiersModelFamily"
Cohesion: 0.22
Nodes (8): MiersIqModel, MiersIqSnapshot, MiersModelFamily, DEEPSEEK, GPT55, LUNA, SOL, TERRA

### Community 8 - "MieRS MieBot 插件"
Cohesion: 0.29
Nodes (6): MieRS MieBot 插件, 制品, 图片预览脚本, 指令, 构建, 配置

## Knowledge Gaps
- **32 isolated node(s):** `SOL`, `TERRA`, `LUNA`, `GPT55`, `DEEPSEEK` (+27 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersConfiguration` connect `MiersConfiguration` to `MiersConfiguration.kt`?**
  _High betweenness centrality (0.135) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `MiersRequestQueue` (e.g. with `.`capacity includes running and waiting tasks and rejects a full queue`()` and `.`close cancels active work is idempotent and rejects later submissions`()`) actually correct?**
  _`MiersRequestQueue` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `SOL`, `TERRA`, `LUNA` to the rest of the system?**
  _32 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MiersConfiguration.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.11931818181818182 - nodes in this community are weakly interconnected._
- **Should `MiersPlugin` be split into smaller, more focused modules?**
  _Cohesion score 0.13846153846153847 - nodes in this community are weakly interconnected._
- **Should `qqbot-plugin-schema.json` be split into smaller, more focused modules?**
  _Cohesion score 0.07692307692307693 - nodes in this community are weakly interconnected._