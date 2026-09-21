# Graph Report - AIRS  (2026-09-22)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 271 nodes · 577 edges · 14 communities (11 shown, 1 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 28 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `2c299ea2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MiersConfiguration
- MiersPlugin
- MiersIqImageRenderer
- CodexRadarIqClient
- commandAliases
- MiersPluginFactoryTest
- properties
- CodexRadarIqClientTest
- MiersRequestQueue
- MiersCooldownTracker
- MiersIqImageRendererTest
- MieRS MieBot 插件

## God Nodes (most connected - your core abstractions)
1. `CodexRadarIqClient` - 31 edges
2. `MiersPlugin` - 22 edges
3. `MiersIqImageRenderer` - 19 edges
4. `MiersPluginFactoryTest` - 19 edges
5. `MiersConfiguration` - 17 edges
6. `MiersRequestQueue` - 16 edges
7. `CodexRadarIqClientTest` - 15 edges
8. `RecordingHttpClient` - 12 edges
9. `MiersCooldownTracker` - 11 edges
10. `MiersCommandAliases` - 10 edges

## Surprising Connections (you probably didn't know these)
- `MiersPlugin` --calls--> `MiersCooldownTracker`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactory.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersCooldownTracker.kt
- `MiersPlugin` --calls--> `MiersRequestQueue`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactory.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersRequestQueue.kt
- `MiersPluginFactoryTest` --calls--> `MiersIqModel`  [EXTRACTED]
  src/test/kotlin/com/mieai/qqbot/plugin/miers/MiersPluginFactoryTest.kt → src/main/kotlin/com/mieai/qqbot/plugin/miers/MiersIqImageRenderer.kt

## Import Cycles
- None detected.

## Communities (14 total, 1 thin omitted)

### Community 0 - "MiersConfiguration"
Cohesion: 0.12
Nodes (13): immutableSortedSet(), MiersCommandAliases, MiersConfiguration, MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, requireConfiguration(), validateCommandAlias() (+5 more)

### Community 1 - "MiersPlugin"
Cohesion: 0.13
Nodes (14): BotPlugin, BotPluginFactory, EventSubscription, InboundMessage, PluginRuntimeContext, ByteArray, PluginEvent, MiersCommand (+6 more)

### Community 2 - "MiersIqImageRenderer"
Cohesion: 0.17
Nodes (12): Color, Font, FontMetrics, Graphics2D, ByteArray, MiersIqImageRenderer, MiersModelFamily, DEEPSEEK (+4 more)

### Community 3 - "CodexRadarIqClient"
Cohesion: 0.19
Nodes (11): JsonReader, RuntimeException, CodexRadarIqClient, CodexRadarIqException, ComboFields, comboKey(), ExpectedCombo, ByteArray (+3 more)

### Community 4 - "commandAliases"
Cohesion: 0.08
Nodes (25): additionalProperties, default, description, properties, required, type, help, query (+17 more)

### Community 5 - "MiersPluginFactoryTest"
Cohesion: 0.26
Nodes (5): GroupMemberRole, MessageTargetType, PluginTestContext, PluginEvent, MiersPluginFactoryTest

### Community 6 - "properties"
Cohesion: 0.09
Nodes (21): additionalProperties, items, type, minimum, type, maxLength, minLength, pattern (+13 more)

### Community 7 - "CodexRadarIqClientTest"
Cohesion: 0.29
Nodes (6): PluginHttpClient, PluginHttpRequest, CodexRadarIqClientTest, Combo, PluginHttpResponse, RecordingHttpClient

### Community 8 - "MiersRequestQueue"
Cohesion: 0.19
Nodes (6): AutoCloseable, IllegalArgumentException, ActiveTask, MiersRequestQueue, QueuedTask, MiersRequestQueueTest

### Community 9 - "MiersCooldownTracker"
Cohesion: 0.19
Nodes (6): Accepted, Limited, MiersCooldownDecision, MiersCooldownReservation, MiersCooldownTracker, MiersCooldownTrackerTest

### Community 11 - "MieRS MieBot 插件"
Cohesion: 0.25
Nodes (7): MieRS MieBot 插件, 制品, 实时数据, 指令, 指令别名, 构建, 配置

## Knowledge Gaps
- **49 isolated node(s):** `ExpectedCombo`, `SOL`, `TERRA`, `LUNA`, `GPT55` (+44 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 69 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersIqModel` connect `CodexRadarIqClient` to `MiersIqImageRenderer`, `MiersIqImageRendererTest`, `MiersPluginFactoryTest`?**
  _High betweenness centrality (0.267) - this node is a cross-community bridge._
- **Why does `MiersPlugin` connect `MiersPlugin` to `MiersRequestQueue`, `MiersCooldownTracker`?**
  _High betweenness centrality (0.244) - this node is a cross-community bridge._
- **Why does `MiersPluginFactoryTest` connect `MiersPluginFactoryTest` to `CodexRadarIqClient`?**
  _High betweenness centrality (0.169) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MiersPlugin` (e.g. with `MiersCooldownTracker` and `MiersRequestQueue`) actually correct?**
  _`MiersPlugin` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 3 inferred relationships involving `MiersIqImageRenderer` (e.g. with `.enqueueIqImage()` and `.`model cards render separate thinking strength cells`()`) actually correct?**
  _`MiersIqImageRenderer` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `MiersConfiguration` (e.g. with `.`command aliases render and reject invalid or ambiguous names`()` and `.`defaults and legacy yaml use no command aliases`()`) actually correct?**
  _`MiersConfiguration` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ExpectedCombo`, `SOL`, `TERRA` to the rest of the system?**
  _49 weakly-connected nodes found - possible documentation gaps or missing edges._