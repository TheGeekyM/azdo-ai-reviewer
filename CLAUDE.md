# CLAUDE.md — developer & continuation notes

Context for continuing work on this Rider plugin. Read this before changing things.

## What this is

A standalone **Kotlin IntelliJ-Platform plugin for Rider** that talks to **Azure DevOps Server (on-prem)** and the **Anthropic/OpenAI/Ollama** HTTP APIs directly. It is NOT connected to any MCP server — it cannot call the Roslyn MCP or Claude Code. All intelligence comes from the prompts it sends to the model.

## Build / run loop

```bash
./gradlew buildPlugin   # → build/distributions/azdo-ai-reviewer-1.0.0.zip
```
Install via Settings → Plugins → ⚙ → Install Plugin from Disk → restart Rider.
First build downloads the Rider SDK (~500MB, cached afterward). Build is ~45–60s once cached.

## Architecture (clean layering, deps point downward)

```
ui/            Tool window + panels (Swing). PrListPanel, PrDetailPanel,
               FilesChangedPanel, ReviewResultPanel, PostPreviewDialog.
application/   PrService, ReviewService, CommentService (app services, @Service APP level)
domain/        Pure models: PullRequest, FileDiff (+ UnifiedDiff), ReviewComment, enums
infrastructure/
  azdo/        AzdoHttpClient (OkHttp), DTOs, AzdoException
  ai/          AiReviewProvider interface + ClaudeProvider/OpenAiProvider/OllamaProvider,
               PromptBuilder, ReviewResponseParser, AiProviderFactory, ClaudeOAuth
  cache/       PrCacheService (Caffeine)
settings/      AzdoSettings (PersistentStateComponent), AzdoSettingsState, Configurable
```

## Hard-won facts (verified by curl/javap — do not re-discover)

### Azure DevOps Server (on-prem, e.g. https://your-server.example.com)
- Auth header: `Basic base64(":" + PAT)` (PAT as password, empty username).
- The **collection** must be in the base URL → `https://your-server.example.com/DefaultCollection`.
  `parseAndSaveRepoUrl()` appends `/DefaultCollection` for non-cloud servers automatically.
- `…/_apis/profile/profiles/me` is **cloud-only**; on-prem returns 404.
  Use `…/_apis/connectionData` to get the authenticated user id. ← this is the "test connection".
- **Collection-level PR list** is the key endpoint — one call, all repos:
  `{base}/_apis/git/pullrequests?status=active&api-version=5.0`
  Each PR carries `repository.id`, `repository.project.name`, `createdBy.imageUrl`,
  `mergeStatus` (succeeded/conflicts/failure), and `reviewers[]`.
- Diffs need the iteration's commits: `sourceRefCommit` (new) and `commonRefCommit` (base).
  Fetch file content with `versionDescriptor.version=<sha>&versionDescriptor.versionType=commit&$format=text`
  (URL-encode the path). Added files have no base content → 404 → treat as empty.
- API version `5.0` works for on-prem; `7.1` works too but 5.0 is safest.
- `AzdoHttpClient` keeps a `prRepoMap` (prId → project, repoId). It MUST be a single shared
  instance (see PrService) or diffs/comments lose the mapping. Don't recreate it per call.

### Claude
- Default model `claude-sonnet-4-6` (verified valid API id). Model field blank → default.
- API-key mode: header `x-api-key` + `anthropic-version: 2023-06-01`.
- **Subscription OAuth** (Pro/Max/Team) — UNOFFICIAL, uses Claude Code's public client_id
  `9d1c250a-e61b-44d9-88ed-5944d1962f5e`. PKCE S256. Authorize at
  `https://claude.com/cai/oauth/authorize`, token at `https://platform.claude.com/v1/oauth/token`.
  Redirect URI is Anthropic's own domain, so we can't run a local callback server → the user
  pastes the `code#state` manually. In OAuth mode: header `Authorization: Bearer <token>` +
  `anthropic-beta: oauth-2025-04-20`, and a system prompt identifying as Claude Code.
  NOTE: this may violate Anthropic ToS; user accepted the risk.

### Rider / IntelliJ gotchas
- **C# diagnostics from ReSharper are NOT reachable** via standard plugin API (they live in the
  out-of-process backend over RdProtocol). `DaemonCodeAnalyzer.getHighlights` won't return them.
  So review quality comes from a strong prompt + full-file context, not live diagnostics.
- **Never render emoji/HTML in a JEditorPane on Linux** — it does slow font-fallback and FROZE
  the EDT on every click. The detail pane is a plain `JTextPane` with `StyledDocument`. Keep it that way.
- File open / Apply Fix must be non-blocking: bail if `DumbService.isDumb`, use
  `ReadAction.nonBlocking{…}.inSmartMode()` inside a `Task.Backgroundable`. Edits go through
  `WriteCommandAction` (undoable).
- Closeable PR tabs require `canCloseContents="true"` on `<toolWindow>` in plugin.xml AND
  `content.isCloseable = true`. Both are needed.
- git checkout: `git4idea` (plugin id `Git4Idea`, gradle dep `vcs-git`).
  `GitFetchSupport.fetchSupport(project).fetchAllRemotes(...)` then
  `GitBrancher.getInstance(project).checkout(branch, false, repos) { …callback… }`.
- kotlinx-coroutines is **bundled** with the platform — do NOT add it as a Gradle dependency
  (causes a verifyPluginConfiguration failure). Just import and use it.
- Target Java 17 (SDK requires it even on JDK 21): `sourceCompatibility/targetCompatibility = 17`,
  `kotlinOptions.jvmTarget = "17"`.

## Review behavior (what the AI gets)

- `ReviewService` sends per-file: a **compact unified diff** (changed lines + 3 context lines,
  added lines annotated `+code    [line 42]`) plus the **full new file** as context-only.
- `PromptBuilder` uses a detailed **C# checklist** when language is csharp, generic otherwise.
- The model returns JSON per finding: `line, endLine, severity, category, comment, suggestion,
  suggestedCode, friendlyComment`. `friendlyComment` is what gets posted (warm, varied, no AI/Azure/
  severity mentions); `comment`/`suggestion` feed the details pane; `suggestedCode` powers Apply Fix.
- Errors are surfaced, not swallowed: if every file fails, `reviewPr` throws with the first error.
  Reviews are not cached as empty (so a fixed key works on next run).

## Common next tasks / ideas

- "Assigned to me / Needs my review" action filter (needs current-user id from connectionData).
- Optional `InspectCode` CLI pass for real ReSharper diagnostics (slow; behind a setting).
- Multi-pass review (bugs / security / architecture) for higher recall.
- Reply-to-thread and resolve-thread support on the Azure DevOps side.

## Don't

- Don't add kotlinx-coroutines as a dependency.
- Don't put emoji/HTML in JEditorPane.
- Don't recreate AzdoHttpClient per call (breaks prRepoMap).
- Don't assume cloud Azure DevOps endpoints — this targets on-prem Server.
- Don't commit tokens/keys. Secrets live in IntelliJ PasswordSafe (OS keychain), never in state XML.
