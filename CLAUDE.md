# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

RikkaHub is a native Android LLM chat client (Kotlin + Jetpack Compose, Material 3 Expressive) that
supports switching between multiple AI providers. It does not accept PRs.

**Read `AGENTS.md` first** — it covers the Material 3 Expressive design requirements, the core domain
concepts (Assistant, Conversation, UIMessage, MessageNode, message transformers), and i18n rules.

## Commands

```bash
./gradlew assembleGithubDebug    # Debug APK for the github flavor
./gradlew assembleGithubRelease  # GitHub Release APK
./gradlew bundlePlayRelease      # Google Play AAB
./gradlew test                   # JVM unit tests across all modules
./gradlew lint                   # Android Lint
./gradlew spotlessApply          # ktlint-format Kotlin files changed vs origin/master
./gradlew spotlessCheck          # Format check only
./gradlew :app:testGithubDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.SettingsFieldsTest"  # single test class
```

- Product flavor dimension `distribution`: `github` (default) and `play`, same applicationId.
  Channel differences gate on `BuildConfig.IS_PLAY_STORE` (Play build can't self-update).
- Building the app requires `app/google-services.json` (not committed).
- i18n: manage `strings.xml` entries with the Bun CLI in `tools/i18n` (see the `i18n-localization`
  skill, or `bun tools/i18n/src/cli.ts --help` from the repo root). Other tools live in
  `tools/models-dev` (models.dev catalog sync) and `tools/trace` (stream replay).

## Module Structure

- **app** — application: UI pages, ViewModels, services, repositories, DataStore/Room, MCP, tools
- **ai** — AI SDK: provider abstraction, streaming, agent loop (no Android/app dependencies)
- **common** — utilities; **material3** — Material color extensions; **ui** — reusable Compose
  components independent of app logic
- **search** — search provider SDK (Exa, Tavily, Zhipu, Brave, SearXNG, …); **speech** — TTS/ASR
- **document** — PDF/DOCX/PPTX/EPUB parsing; **mediagen** — image/video generation
- **web** — embedded Ktor server hosting the React frontend in `web-ui/`
- **workspace** — sandboxed per-workspace filesystem/shell exposed to the AI as tools
- **terminal** — termux `terminal-emulator` core; local terminals use `LocalPtyTransport` (PRoot),
  SSH workspaces use `SshTerminalTransport`
- **oauth**, **app:baselineprofile** — supporting modules
- `build-logic/` — included build with `AndroidLibraryConventionPlugin` and
  `AndroidLibraryComposeConventionPlugin`

## Architecture

### Chat generation flow

UI → `ChatVM` (thin facade over `ChatService`) → `ChatService` → `ai` module agent loop:

1. `ChatService.sendMessage()` cancels the previous generation Job, finishes interrupted tool calls,
   applies assistant regex rules to user input, appends the user message.
2. `ChatService.runTurn()` builds an `AgentRequest` via `data/ai/ChatRequestFactory` (and tools via
   `data/ai/tools/ChatToolFactory`), then calls `runAgent()` from the `ai` module, collecting
   `AgentEvent`s and persisting through `ConversationRepository`.
3. The agent loop (`ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt`) iterates steps: input
   transformers run, a provider streams `StreamChunk`s (`ai/.../ui/StreamChunk.kt`), output
   transformers run (real / visual / on-finish), then any pending tool calls execute (with an
   approval state machine: Auto → Pending → Approved/Denied/Answered) and the loop repeats until no
   tools remain.

Note: `docs/references/chat-generation-pipeline.md` documents this flow in detail but predates a
refactor — there is no `GenerationHandler` class in code anymore; its role is
`ChatService.runTurn` + `ai/agent/AgentLoop.kt`. Transformer lists in that doc are still useful.

- Input transformers (`data/ai/transformers/`) run before sending (template rendering, prompt
  injection, placeholder replacement, OCR, document-to-prompt, time reminder, workspace reminder).
- Output transformers handle `<think>` tag extraction, assistant regex replacements, and base64
  image persisting; they support `visualTransform()` (streaming UI only) and `onGenerationFinish()`.
- Tool merge order in `ChatToolFactory`: search → local tools → conversation tools → workspace →
  skills → MCP (`mcp__{server}__{tool}`) → memory tools.

### Conversation state (critical)

Once persisted, a `Conversation` holds a `ConversationConfig` snapshot (model, reasoning, search,
MCP, workspace, skills). Read it via `Settings.getAssistantOf(conversation)` /
`Settings.getChatOf(conversation)` — not the assistant directly. After load, the in-memory
`ConversationSession` (ref-counted, managed by `ConversationSessionManager`) is the single source of
truth: mutate conversations only through `ChatService` (`session.update { ... }` + `flush()`), never
by saving a whole `Conversation` snapshot read back from the DB. Deletions also go through
`ChatService` so a running generation can't re-insert the row.

### Providers (`ai` module)

`Provider<T : ProviderSetting>` interface (`listModels`/`generateText`/`streamText`/embeddings/image)
with implementations in `ai/.../provider/providers/` (OpenAI incl. Responses API, Google/Vertex,
Claude, ChatGPT account, Grok), registered in `ProviderManager`. `ProviderSetting` is a sealed
hierarchy serialized into Settings; SSE decoding is per-provider `StreamDecoder`s producing
`StreamChunk`s folded into `UIMessage`s by `StreamChunkHandler`.

### Persistence & settings

- **Room**: `data/db/AppDatabase.kt` (DAOs in `db/dao/`, entities in `db/entity/`, manual migrations
  in `db/migrations/`, FTS in `db/fts/`). Add migrations and bump the version; don't use
  destructive fallback.
- **Settings**: one big `@Serializable` `Settings` data class in `data/datastore/Settings.kt`,
  persisted via Preferences DataStore (`PreferencesStore.kt`). New settings fields need a line in
  `SettingsFields.kt` (`SETTINGS_FIELDS` handles read/write/reset — see its tests) plus normalization
  in `SettingsNormalization.kt` if needed; migrations live in `datastore/migration/`.

### DI, MCP, Skills, Navigation

- **Koin**: modules in `app/.../di/` — `AppModule`, `DataSourceModule`, `RepositoryModule`,
  `ViewModelModule`.
- **MCP**: `data/ai/mcp/McpManager.kt` (pooled sessions via `McpSessionRegistry`, OAuth via
  `McpOAuthCoordinator`).
- **Skills**: `data/skills/SkillManager.kt` loads user + bundled skills, exposed as tools via
  `data/ai/tools/SkillsTools.kt`.
- **Navigation 3**: routes are `sealed interface Screen : NavKey` in `RouteActivity.kt`, rendered in
  `AppRoutes()` there; helpers in `ui/context/NavContext.kt`.

## Tests

Plain JVM unit tests (JUnit 4 + kotlinx-coroutines-test, no Robolectric). App tests mirror main
packages under `app/src/test/java/me/rerere/rikkahub/`; the `ai` module has per-provider
request/decoding tests under `ai/src/test/java/me/rerere/ai/`. Add a focused test class per unit
(e.g. `SettingsFieldsTest`) when changing settings fields or transformers.
