# Graph Report - AIRS  (2026-10-11)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 273 nodes · 577 edges · 14 communities
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 32 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `26eeeaa6`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MiersIqImageRenderer
- MiersPlugin
- MiersPluginFactoryTest
- CodexRadarIqClientTest
- commandAliases
- MiersConfiguration.kt
- properties
- MiersRequestQueue
- CodexRadarIqClient
- MiersCooldownTracker
- MieRS MieBot 插件
- MiersModelFamily

## God Nodes (most connected - your core abstractions)
1. `MiersIqImageRenderer` - 24 edges
2. `MiersPlugin` - 22 edges
3. `CodexRadarIqClient` - 19 edges
4. `MiersPluginFactoryTest` - 19 edges
5. `MiersConfiguration` - 17 edges
6. `MiersRequestQueue` - 16 edges
7. `CodexRadarIqClientTest` - 14 edges
8. `MiersIqImageRendererTest` - 12 edges
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

### Community 1 - "MiersPlugin"
Cohesion: 0.12
Nodes (17): BotPlugin, BotPluginFactory, EventSubscription, GroupMemberRole, InboundMessage, MessageTargetType, PluginRuntimeContext, ByteArray (+9 more)

### Community 2 - "MiersPluginFactoryTest"
Cohesion: 0.20
Nodes (5): PluginTestContext, MiersCommandAliases, MiersConfiguration, MiersConfigurationTest, MiersPluginFactoryTest

### Community 3 - "CodexRadarIqClientTest"
Cohesion: 0.22
Nodes (8): PluginHttpClient, PluginHttpRequest, CodexRadarIqClientTest, PluginHttpResponse, PendingRequest, QueuedHttpClient, RecordingHttpClient, URI

### Community 4 - "commandAliases"
Cohesion: 0.08
Nodes (25): additionalProperties, default, description, properties, required, type, help, query (+17 more)

### Community 5 - "MiersConfiguration.kt"
Cohesion: 0.16
Nodes (10): immutableSortedSet(), MiersConfigurationCodec, MiersConfigurationException, MiersConfigurationStore, requireConfiguration(), validateCommandAlias(), validateGroupId(), validateGroupIds() (+2 more)

### Community 6 - "properties"
Cohesion: 0.09
Nodes (21): additionalProperties, items, type, minimum, type, maxLength, minLength, pattern (+13 more)

### Community 7 - "MiersRequestQueue"
Cohesion: 0.19
Nodes (6): AutoCloseable, IllegalArgumentException, ActiveTask, MiersRequestQueue, QueuedTask, MiersRequestQueueTest

### Community 8 - "CodexRadarIqClient"
Cohesion: 0.27
Nodes (7): JsonReader, RuntimeException, CodexRadarIqClient, CodexRadarIqException, ComboReadResult, ExpectedCombo, PluginHttpResponse

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
- **49 isolated node(s):** `SOL`, `ASTRA`, `TERRA`, `LUNA`, `GPT55` (+44 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 68 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MiersIqModel` connect `MiersIqImageRenderer` to `CodexRadarIqClient`, `MiersPluginFactoryTest`?**
  _High betweenness centrality (0.261) - this node is a cross-community bridge._
- **Why does `MiersPlugin` connect `MiersPlugin` to `MiersCooldownTracker`, `MiersRequestQueue`?**
  _High betweenness centrality (0.247) - this node is a cross-community bridge._
- **Why does `MiersIqImageRenderer` connect `MiersIqImageRenderer` to `MiersPlugin`?**
  _High betweenness centrality (0.189) - this node is a cross-community bridge._
- **Are the 7 inferred relationships involving `MiersIqImageRenderer` (e.g. with `.enqueueIqImage()` and `.`model cards wrap at five and rank columns continue in global IQ order`()`) actually correct?**
  _`MiersIqImageRenderer` has 7 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `MiersPlugin` (e.g. with `MiersCooldownTracker` and `MiersRequestQueue`) actually correct?**
  _`MiersPlugin` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `MiersConfiguration` (e.g. with `.`command aliases render and reject invalid or ambiguous names`()` and `.`defaults and legacy yaml use no command aliases`()`) actually correct?**
  _`MiersConfiguration` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `SOL`, `ASTRA`, `TERRA` to the rest of the system?**
  _49 weakly-connected nodes found - possible documentation gaps or missing edges._