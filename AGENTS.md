# Repository Guidelines

Native Android LLM chat client (Kotlin + Jetpack Compose, Material 3 Expressive) with a multi-provider
`ai` SDK, an embedded Ktor web server serving a React client (`web-ui/`), and a PRoot-based agent
workspace. The project **does not accept pull requests**. `CLAUDE.md` exists for Claude Code; this file
is the canonical summary.

**This repo is an independent fork** (decided 2026-11-21): upstream `re-ovo/rikkahub` is no longer
synced, merged, or tracked as a remote. `origin` is this project's own repository — never add the
upstream remote, fetch it, or suggest porting its commits.

## Setup and verification

- **JDK 21.** CI uses the JetBrains Runtime 21 (`daily-build.yml`).
- **`google-services.json` is required to build.** CI writes it to `app/google-services.json` (secret).
  This checkout only has ignored dummies at `app/src/{debug,release}/google-services.json`; a fresh
  clone fails at the Google Services plugin without one.
- **Every app build also builds the frontend.** `:web:preBuild` runs `buildWebUi` → `pnpm run build` in
  `web-ui/`, so install once with `pnpm --dir web-ui install` (pnpm 11 + Node 22) or the Gradle build
  fails even if you never touch the web UI.

```bash
pnpm --dir web-ui install                 # once, before the first Gradle build
./gradlew assembleGithubDebug             # github-flavor debug APK
./gradlew assembleGithubRelease           # github-flavor release APK
./gradlew bundlePlayRelease               # Play AAB
./gradlew :app:compileGithubDebugKotlin   # fastest compile-only check (~1-5 min)
./gradlew test                            # JVM unit tests, all modules
./gradlew :app:testGithubDebugUnitTest --tests "me.rerere.rikkahub.data.datastore.SettingsFieldsTest"
./gradlew spotlessApply                   # ktlint, only files changed vs origin/master
```

- `spotlessApply` uses `ratchetFrom("origin/master")`, so only files changed since that commit
  (untracked new files included) are checked; run `git fetch origin` first. `spotlessCheck` is the
  check-only variant.
- `./gradlew lint` is slow; iterate with the compile check plus focused tests.
- Product flavor dimension `distribution`: `github` (default) and `play`, same applicationId. Gate
  channel differences on `BuildConfig.IS_PLAY_STORE` (Play build cannot self-update).
- Tests are plain JUnit 4 + kotlinx-coroutines-test (no Robolectric) and mirror `main` packages. For
  provider work, `ai/.../provider/stream/StreamTraceReplayTest.kt` replays recorded SSE traces from
  `ai/src/test/resources/stream-traces/`; record new ones with `tools/trace`
  (`bun run trace -- traces.yml`, see `tools/trace/README.md`).

## Modules

- **app** — UI pages, ViewModels, services, repositories, DataStore/Room, MCP, tools.
- **ai** — provider abstraction, streaming, agent loop. Android library, but must not depend on `app`.
- **common**, **material3**, **ui** — utilities, Material color extensions, reusable Compose
  components that must not depend on app logic.
- **search**, **speech**, **document**, **mediagen**, **oauth** — search SDK, TTS/ASR, PDF/DOCX/PPTX/
  EPUB parsing, image/video generation, OAuth.
- **web** — Ktor server + static assets generated from `web-ui/` (don't hand-edit
  `web/src/main/resources/static`).
- **workspace** — sandboxed per-workspace filesystem/shell exposed as AI tools.
- **terminal** — termux `terminal-emulator` core, package `com.termux.terminal` must stay binary
  compatible with the `terminal-view` aar; see `terminal/README.md`.
- **build-logic** — included build with `AndroidLibraryConventionPlugin` and
  `AndroidLibraryComposeConventionPlugin`.

## Architecture essentials

**Chat generation:** `ChatVM` (thin facade) → `ChatService.sendMessage()`/`runTurn()` → `runAgent()` in
`ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt`. The loop runs input transformers, streams a
provider into `StreamChunk`s, runs output transformers (real / `visualTransform()` / `onGenerationFinish()`),
executes pending tool calls (approval state machine Auto → Pending → Approved/Denied/Answered) and
repeats. `docs/references/chat-generation-pipeline.md` predates the refactor: there is no
`GenerationHandler` class; only its transformer list is still useful.

**Conversation state (critical):** a persisted `Conversation` carries a `ConversationConfig` snapshot
(chat model, reasoning, search, MCP, workspace, skills). Read it through
`Settings.getAssistantOf(conversation)` / `Settings.getChatModelOf(conversation)` (extension functions in
`data/model/ConversationConfig.kt`), never the assistant directly. Once loaded, the in-memory
`ConversationSession` is the single source of truth: mutate only via `ChatService` (`session.update { ... }`
then `flush()`); never persist a whole `Conversation` snapshot read back from the DB, and delete through
`ChatService` so a running generation cannot re-insert the row. Message branching lives in `MessageNode`
(`messages` + `selectIndex`).

**Settings:** one `@Serializable` `Settings` in `data/datastore/Settings.kt`, persisted via Preferences
DataStore. Adding a field also requires an entry in `data/datastore/SettingsFields.kt` (`SETTINGS_FIELDS`
drives read/write/reset; covered by `SettingsFieldsTest`) plus normalization in `SettingsNormalization.kt`
when needed. Migrations live in `data/datastore/migration/`.

**Room:** `data/db/AppDatabase.kt`, DAOs/entities under `db/`, manual migrations in `db/migrations/`, FTS
in `db/fts/`. Bump the DB version and add a migration; destructive fallback is not used.

**Providers:** `Provider<T : ProviderSetting>` in `ai/.../provider/`, implementations under
`ai/.../provider/providers/` (OpenAI incl. Responses API, Google/Vertex, Claude, ChatGPT account, Grok),
registered in `ProviderManager`. `ProviderSetting` is serialized into `Settings`; per-provider
`StreamDecoder`s emit `StreamChunk`s folded into `UIMessage`s by `StreamChunkHandler`.

**Tools:** `data/ai/tools/`. `ChatToolFactory.createTools()` builds them in this order: memory → search
→ local tools → conversation tools → workspace → skills → MCP (`mcp__{server}__{tool}`). Oversized tool
output is truncated to a file only when a local Rootfs workspace can read it.

**Other wiring:** Koin modules in `app/.../di/`; MCP in `data/ai/mcp/McpManager.kt`; Skills in
`data/skills/SkillManager.kt` exposed via `data/ai/tools/SkillsTools.kt`; Navigation 3 routes are a
`sealed interface Screen : NavKey` in `RouteActivity.kt`, rendered by `AppRoutes()` there.

## UI conventions

Follow Material 3 Expressive (`MaterialExpressiveTheme` in `ui/theme/Theme.kt`), not baseline M3: prefer
expressive component variants, `MaterialShapes`, emphasized typography (`titleSmallEmphasized`) and
built-in motion over hand-rolled equivalents. Settings/form pages use segmented groups via `CardGroup`
(`ui/components/ui/CardGroup.kt`) instead of a card with dividers. Favor larger corner radii,
filled/tonal containers and whole-row click targets.

## Internationalization

Strings live in `app/src/main/res/values*/strings.xml` (feature modules such as `search` keep their own).
Page strings use a page prefix (e.g. `setting_page_`); use `stringResource(...)` in Compose. Manage keys
with the Bun CLI in `tools/i18n` (`bun run i18n add <key> "<text>"`, `list --missing`, `remove`), which
handles every locale at once. Unless localization is requested, it is fine to ship English-only text.

## Web client (`web-ui/`)

Separate React Router 7 + TypeScript + Tailwind app; read `web-ui/CLAUDE.md` before changing it (the
stub `web-ui/AGENTS.md` points there). Its `app/types/` mirror Kotlin types — update both sides together
and run `pnpm --dir web-ui typecheck`. `pnpm run build` (run automatically by Gradle) copies the build
into `web/src/main/resources/static`.

## Release

Bump both `versionCode` (increment by 1) and `versionName` in `app/build.gradle.kts`, commit as
`chore: bump version to <version>`, then follow `.agents/skills/publish-release/SKILL.md`: bilingual
changelog aggregated from commits since the last tag (≤10 bullets, no jargon), create the GitHub release
with tag = version (no `v`), upload only the arm64 APK renamed `rikkahub-<version>-arm64-v8a.apk`, and
upload `mapping-<version>.zip` (zipped `mapping.txt`) for crash de-obfuscation. `.github/workflows/daily-build.yml`
publishes a `nightly` prerelease when the last 24h had commits.
