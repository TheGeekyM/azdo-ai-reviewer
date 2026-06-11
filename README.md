# Azure DevOps AI Reviewer — Rider Plugin

A JetBrains Rider plugin that browses **Azure DevOps pull requests** and runs **AI-powered code review** on them (Claude / OpenAI / Gemini / local Ollama). Built and tested against **Azure DevOps Server (on-prem)** and Rider on Ubuntu.

> Status: working MVP. Built with the IntelliJ Platform Gradle plugin against the Rider SDK (`RD 2024.1.4`, compatible up to build `263.*`).

## Demo

https://github.com/TheGeekyM/azdo-ai-reviewer/releases/download/v1.0.3/demo.webm

---

## Features

- **Browse PRs** — lists active pull requests across the whole collection in one call. Card UI with author avatar, status badge, and a red ✗ on the avatar when a PR can't be merged.
- **Filters** — by Creator, Reviewer, and Merge status (devops-style facets).
- **Files Changed** — opens changes in Rider's **native side-by-side diff viewer** (real before/after fetched at the merge-base and PR source commits).
- **AI code review** — reviews **only the changed lines** (compact unified diff + a few lines of context), with a principal-level **C#/.NET** review checklist (async/await, EF Core, nullable safety, DI lifetimes, DDD/Clean Architecture, SOLID, security).
- **Rich results** — circular severity icons, summary chips, and a details pane showing *what's wrong*, *how to fix*, and *suggested code*.
- **Apply Fix** — one click replaces the affected lines in the file with the AI's suggested code (undoable).
- **Post back to Azure DevOps** — line-level comments and a summary, written in a warm, human tone (no AI/tool/severity labels). "Post Selected" shows an **editable preview** before posting.
- **Open file at line** — double-click an issue; if the PR branch isn't checked out, it offers to **fetch + checkout** the branch.
- **Auth** — Azure DevOps via PAT; Claude via API key **or** subscription sign-in (Pro/Max/Team OAuth).

---

## Build

Requires **JDK 17+** (JDK 21 fine) and the bundled Gradle wrapper.

```bash
./gradlew buildPlugin
# → build/distributions/azdo-ai-reviewer-1.0.0.zip
```

## Install in Rider

```
Settings → Plugins → ⚙ → Install Plugin from Disk…
→ select build/distributions/azdo-ai-reviewer-1.0.0.zip
→ restart Rider
```

## Configure

**Settings → Tools → Azure DevOps AI Reviewer**

| Field | Example |
|---|---|
| Repo URL | `https://your-server.example.com` (bare server URL — `/DefaultCollection` is added automatically) — or a full repo URL |
| Token | your Azure DevOps PAT (scopes: Code read, Pull Request Threads read/write) |
| AI Provider | `claude` |
| API Key | your Anthropic key — **or** click *Sign in with Claude* for subscription auth |

Use **Test Connection** (Azure DevOps) and **Test AI Key** (provider) to verify before reviewing.

---

## Usage

1. Open the **AzDO PR Reviewer** tool window (right side).
2. **Refresh** → pick a PR (double-click opens it in a closeable tab).
3. **AI Review** tab → **Run AI Review**.
4. Single-click an issue to see details; **Apply Fix** to patch the file; **Post Selected** (editable) to comment on the PR.

---

## Tech stack

- Kotlin + IntelliJ Platform SDK (Rider, type `RD`)
- OkHttp3 (HTTP), kotlinx.serialization (JSON), Caffeine (cache)
- git4idea for branch fetch/checkout
- Anthropic / OpenAI / Ollama HTTP APIs

See [CLAUDE.md](./CLAUDE.md) for architecture and developer notes.
