# Graph Report - AIRS  (2026-08-06)

## Corpus Check
- 17 files · ~20,284 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 251 nodes · 507 edges · 15 communities (13 shown, 2 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 18 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- MiersConfiguration.kt
- MiersPlugin
- qqbot-plugin-schema.json
- MiersIqImageRenderer
- MiersConfiguration
- MiersRequestQueue
- MiersCooldownTracker
- CodexRadarIqClient
- MieRS MieBot 插件
- MiersIqImageRendererTest
- MiersConfigurationTest
- CodexRadarIqClientTest

## God Nodes (most connected - your core abstractions)
1. `CodexRadarIqClient` - 27 edges
2. `MiersConfiguration` - 20 edges
3. `MiersIqImageRenderer` - 18 edges
4. `MiersPlugin` - 18 edges
5. `MiersRequestQueue` - 15 edges
6. `MiersPluginFactoryTest` - 15 edges
7. `CodexRadarIqClientTest` - 12 edges
8. `MiersConfigurationStore` - 11 edges
9. `MiersCooldownTracker` - 10 edges
10. `RecordingHttpClient` - 9 edges

## Surprising Connections (you probably didn't know these)
- `completedModels()` --references--> `MiersIqModel`  [EXTRACTED]
  src/test/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactoryTest.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersIqImageRenderer.kt

## Import Cycles
- None detected.

## Communities (15 total, 2 thin omitted)

### Community 0 - "MiersConfiguration.kt"
Cohesion: 0.12
Nodes (15): defaults(), immutableSortedSet(), load(), MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, open(), parse() (+7 more)

### Community 1 - "MiersPlugin"
Cohesion: 0.13
Nodes (15): BotPlugin, BotPluginFactory, EventSubscription, InboundMessage, PluginRuntimeContext, ByteArray, PluginEvent, MiersCommand (+7 more)

### Community 2 - "qqbot-plugin-schema.json"
Cohesion: 0.08
Nodes (25): blockedGroups, cooldownMinutes, maxQueue, queueFullMessage, additionalProperties, items, type, minimum (+17 more)

### Community 3 - "MiersIqImageRenderer"
Cohesion: 0.17
Nodes (13): Color, Font, FontMetrics, Graphics2D, color(), ByteArray, MiersIqImageRenderer, MiersModelFamily (+5 more)

### Community 4 - "MiersConfiguration"
Cohesion: 0.25
Nodes (7): GroupMemberRole, MessageTargetType, PluginTestContext, MiersConfiguration, completedModels(), PluginEvent, MiersPluginFactoryTest

### Community 5 - "MiersRequestQueue"
Cohesion: 0.18
Nodes (6): AutoCloseable, IllegalArgumentException, ActiveTask, MiersRequestQueue, QueuedTask, MiersRequestQueueTest

### Community 6 - "MiersCooldownTracker"
Cohesion: 0.19
Nodes (6): Accepted, Limited, MiersCooldownDecision, MiersCooldownReservation, MiersCooldownTracker, MiersCooldownTrackerTest

### Community 7 - "CodexRadarIqClient"
Cohesion: 0.18
Nodes (11): JsonReader, RuntimeException, CodexRadarIqClient, CodexRadarIqException, ComboFields, comboKey(), ExpectedCombo, ByteArray (+3 more)

### Community 8 - "MieRS MieBot 插件"
Cohesion: 0.25
Nodes (7): MieRS MieBot 插件, 制品, 图片预览脚本, 实时数据, 指令, 构建, 配置

### Community 14 - "CodexRadarIqClientTest"
Cohesion: 0.29
Nodes (6): PluginHttpClient, PluginHttpRequest, CodexRadarIqClientTest, Combo, PluginHttpResponse, RecordingHttpClient

## Knowledge Gaps
- **34 isolated node(s):** `ExpectedCombo`, `SOL`, `TERRA`, `LUNA`, `GPT55` (+29 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersIqModel` connect `CodexRadarIqClient` to `MiersIqImageRendererTest`, `MiersIqImageRenderer`, `MiersConfiguration`?**
  _High betweenness centrality (0.262) - this node is a cross-community bridge._
- **Why does `MiersConfiguration` connect `MiersConfiguration` to `MiersConfiguration.kt`?**
  _High betweenness centrality (0.235) - this node is a cross-community bridge._
- **Why does `completedModels()` connect `MiersConfiguration` to `CodexRadarIqClient`?**
  _High betweenness centrality (0.168) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MiersIqImageRenderer` (e.g. with `.enqueueIqImage()` and `.`rendered output is a non-empty 1400 by 1750 png with bars and guides`()`) actually correct?**
  _`MiersIqImageRenderer` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `MiersRequestQueue` (e.g. with `.`capacity includes running and waiting tasks and rejects a full queue`()` and `.`close cancels active work is idempotent and rejects later submissions`()`) actually correct?**
  _`MiersRequestQueue` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ExpectedCombo`, `SOL`, `TERRA` to the rest of the system?**
  _34 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MiersConfiguration.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.125 - nodes in this community are weakly interconnected._