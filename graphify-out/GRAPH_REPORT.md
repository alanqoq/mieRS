# Graph Report - AIRS  (2026-09-23)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 281 nodes · 616 edges · 14 communities
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 31 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `930675f7`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MiersIqImageRenderer
- MiersConfiguration
- MiersPlugin
- CodexRadarIqClient
- commandAliases
- MiersPluginFactoryTest
- CodexRadarIqClientTest
- properties
- MiersRequestQueue
- MiersCooldownTracker
- MieRS MieBot 插件
- MiersModelFamily

## God Nodes (most connected - your core abstractions)
1. `CodexRadarIqClient` - 35 edges
2. `MiersIqImageRenderer` - 23 edges
3. `MiersPlugin` - 22 edges
4. `MiersPluginFactoryTest` - 19 edges
5. `MiersConfiguration` - 17 edges
6. `CodexRadarIqClientTest` - 17 edges
7. `MiersRequestQueue` - 16 edges
8. `RecordingHttpClient` - 14 edges
9. `MiersCooldownTracker` - 11 edges
10. `MiersIqModel` - 11 edges

## Surprising Connections (you probably didn't know these)
- `MiersPlugin` --calls--> `MiersCooldownTracker`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactory.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersCooldownTracker.kt
- `MiersPlugin` --calls--> `MiersRequestQueue`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactory.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersRequestQueue.kt
- `MiersPluginFactoryTest` --calls--> `MiersIqModel`  [EXTRACTED]
  src/test/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactoryTest.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersIqImageRenderer.kt

## Import Cycles
- None detected.

## Communities (14 total, 0 thin omitted)

### Community 0 - "MiersIqImageRenderer"
Cohesion: 0.15
Nodes (9): BufferedImage, Color, Font, FontMetrics, Graphics2D, ByteArray, MiersIqImageRenderer, MiersIqModel (+1 more)

### Community 1 - "MiersConfiguration"
Cohesion: 0.12
Nodes (13): immutableSortedSet(), MiersCommandAliases, MiersConfiguration, MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, requireConfiguration(), validateCommandAlias() (+5 more)

### Community 2 - "MiersPlugin"
Cohesion: 0.13
Nodes (14): BotPlugin, BotPluginFactory, EventSubscription, InboundMessage, PluginRuntimeContext, ByteArray, PluginEvent, MiersCommand (+6 more)

### Community 3 - "CodexRadarIqClient"
Cohesion: 0.19
Nodes (11): JsonReader, RuntimeException, CellStats, CodexRadarIqClient, CodexRadarIqException, ComboFields, comboKey(), ExpectedCombo (+3 more)

### Community 4 - "commandAliases"
Cohesion: 0.08
Nodes (25): additionalProperties, default, description, properties, required, type, help, query (+17 more)

### Community 5 - "MiersPluginFactoryTest"
Cohesion: 0.26
Nodes (5): GroupMemberRole, MessageTargetType, PluginTestContext, PluginEvent, MiersPluginFactoryTest

### Community 6 - "CodexRadarIqClientTest"
Cohesion: 0.27
Nodes (6): PluginHttpClient, PluginHttpRequest, CodexRadarIqClientTest, Combo, PluginHttpResponse, RecordingHttpClient

### Community 7 - "properties"
Cohesion: 0.09
Nodes (21): additionalProperties, items, type, minimum, type, maxLength, minLength, pattern (+13 more)

### Community 8 - "MiersRequestQueue"
Cohesion: 0.19
Nodes (6): AutoCloseable, IllegalArgumentException, ActiveTask, MiersRequestQueue, QueuedTask, MiersRequestQueueTest

### Community 9 - "MiersCooldownTracker"
Cohesion: 0.19
Nodes (6): Accepted, Limited, MiersCooldownDecision, MiersCooldownReservation, MiersCooldownTracker, MiersCooldownTrackerTest

### Community 10 - "MieRS MieBot 插件"
Cohesion: 0.25
Nodes (7): MieRS MieBot 插件, 制品, 实时数据, 指令, 指令别名, 构建, 配置

### Community 11 - "MiersModelFamily"
Cohesion: 0.25
Nodes (7): MiersModelFamily, ASTRA, DEEPSEEK, GPT55, LUNA, SOL, TERRA

## Knowledge Gaps
- **50 isolated node(s):** `ExpectedCombo`, `SOL`, `ASTRA`, `TERRA`, `LUNA` (+45 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 70 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersIqModel` connect `MiersIqImageRenderer` to `CodexRadarIqClient`, `MiersPluginFactoryTest`?**
  _High betweenness centrality (0.283) - this node is a cross-community bridge._
- **Why does `MiersPlugin` connect `MiersPlugin` to `MiersRequestQueue`, `MiersCooldownTracker`?**
  _High betweenness centrality (0.242) - this node is a cross-community bridge._
- **Why does `MiersIqImageRenderer` connect `MiersIqImageRenderer` to `MiersPlugin`?**
  _High betweenness centrality (0.186) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `MiersIqImageRenderer` (e.g. with `.enqueueIqImage()` and `.`model cards wrap at five and rank columns continue in global IQ order`()`) actually correct?**
  _`MiersIqImageRenderer` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `MiersPlugin` (e.g. with `MiersCooldownTracker` and `MiersRequestQueue`) actually correct?**
  _`MiersPlugin` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `MiersConfiguration` (e.g. with `.`command aliases render and reject invalid or ambiguous names`()` and `.`defaults and legacy yaml use no command aliases`()`) actually correct?**
  _`MiersConfiguration` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ExpectedCombo`, `SOL`, `ASTRA` to the rest of the system?**
  _50 weakly-connected nodes found - possible documentation gaps or missing edges._