# Graph Report - AIRS  (2026-08-12)

## Corpus Check
- 16 files · ~9,436 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 273 nodes · 532 edges · 14 communities (13 shown, 1 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 28 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `300b8a71`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MiersConfiguration
- MiersPlugin
- qqbot-plugin-schema.json
- MiersIqImageRenderer
- commandAliases
- MiersRequestQueue
- MiersCooldownTracker
- CodexRadarIqClient
- MieRS MieBot 插件
- MiersIqImageRendererTest
- MiersPluginFactoryTest
- CodexRadarIqClientTest

## God Nodes (most connected - your core abstractions)
1. `CodexRadarIqClient` - 27 edges
2. `MiersPlugin` - 20 edges
3. `MiersIqImageRenderer` - 18 edges
4. `MiersConfiguration` - 17 edges
5. `MiersPluginFactoryTest` - 16 edges
6. `MiersRequestQueue` - 15 edges
7. `CodexRadarIqClientTest` - 12 edges
8. `MiersCommandAliases` - 10 edges
9. `MiersCooldownTracker` - 10 edges
10. `RecordingHttpClient` - 9 edges

## Surprising Connections (you probably didn't know these)
- `completedModels()` --references--> `MiersIqModel`  [EXTRACTED]
  src/test/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactoryTest.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersIqImageRenderer.kt

## Import Cycles
- None detected.

## Communities (14 total, 1 thin omitted)

### Community 0 - "MiersConfiguration"
Cohesion: 0.12
Nodes (15): immutableSortedSet(), MiersCommandAliases, MiersConfiguration, MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, open(), requireConfiguration() (+7 more)

### Community 1 - "MiersPlugin"
Cohesion: 0.12
Nodes (15): BotPlugin, BotPluginFactory, EventSubscription, InboundMessage, PluginRuntimeContext, ByteArray, PluginEvent, MiersCommand (+7 more)

### Community 2 - "qqbot-plugin-schema.json"
Cohesion: 0.08
Nodes (25): blockedGroups, cooldownMinutes, maxQueue, queueFullMessage, additionalProperties, items, type, minimum (+17 more)

### Community 3 - "MiersIqImageRenderer"
Cohesion: 0.17
Nodes (13): Color, Font, FontMetrics, Graphics2D, color(), ByteArray, MiersIqImageRenderer, MiersModelFamily (+5 more)

### Community 4 - "commandAliases"
Cohesion: 0.07
Nodes (28): help, query, toggle, additionalProperties, default, description, properties, required (+20 more)

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
Nodes (7): MieRS MieBot 插件, 制品, 实时数据, 指令, 指令别名, 构建, 配置

### Community 10 - "MiersPluginFactoryTest"
Cohesion: 0.25
Nodes (6): GroupMemberRole, MessageTargetType, PluginTestContext, completedModels(), PluginEvent, MiersPluginFactoryTest

### Community 14 - "CodexRadarIqClientTest"
Cohesion: 0.29
Nodes (6): PluginHttpClient, PluginHttpRequest, CodexRadarIqClientTest, Combo, PluginHttpResponse, RecordingHttpClient

## Knowledge Gaps
- **54 isolated node(s):** `ExpectedCombo`, `SOL`, `TERRA`, `LUNA`, `GPT55` (+49 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersIqModel` connect `CodexRadarIqClient` to `MiersIqImageRendererTest`, `MiersPluginFactoryTest`, `MiersIqImageRenderer`?**
  _High betweenness centrality (0.214) - this node is a cross-community bridge._
- **Why does `MiersIqImageRenderer` connect `MiersIqImageRenderer` to `MiersPlugin`, `MiersIqImageRendererTest`?**
  _High betweenness centrality (0.147) - this node is a cross-community bridge._
- **Why does `MiersConfiguration` connect `MiersConfiguration` to `MiersPluginFactoryTest`?**
  _High betweenness centrality (0.135) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MiersIqImageRenderer` (e.g. with `.enqueueIqImage()` and `.`rendered output is a non-empty 1400 by 1750 png with bars and guides`()`) actually correct?**
  _`MiersIqImageRenderer` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `MiersConfiguration` (e.g. with `.`command aliases render and reject invalid or ambiguous names`()` and `.`defaults and legacy yaml use no command aliases`()`) actually correct?**
  _`MiersConfiguration` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ExpectedCombo`, `SOL`, `TERRA` to the rest of the system?**
  _54 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MiersConfiguration` be split into smaller, more focused modules?**
  _Cohesion score 0.12121212121212122 - nodes in this community are weakly interconnected._