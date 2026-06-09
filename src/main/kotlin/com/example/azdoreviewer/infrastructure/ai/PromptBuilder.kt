package com.example.azdoreviewer.infrastructure.ai

object PromptBuilder {

    fun build(request: FileReviewRequest): String {
        val isCSharp = request.language == "csharp"
        val criteria = if (isCSharp) CSHARP_CRITERIA else GENERIC_CRITERIA

        val contextSection = if (!request.fullFileContent.isNullOrBlank()) {
            "\n\nFULL FILE (for context only — so you understand how the changed lines fit in; " +
            "do NOT review unchanged code here):\n" +
            "```${request.language}\n${request.fullFileContent.take(12000)}\n```"
        } else ""

        val expertise = if (isCSharp)
            "You are a principal-level C# / .NET 9 engineer and code reviewer. You have deep, " +
            "battle-tested expertise in ASP.NET Core, Entity Framework Core, async/await, nullable " +
            "reference types, dependency injection, Domain-Driven Design, Clean Architecture, and the " +
            "CQRS/MediatR patterns commonly used in modern .NET solutions."
        else
            "You are a principal-level software engineer and meticulous code reviewer for ${request.language}."

        return """
$expertise

Review the change below for file: ${request.filePath}
Language: ${request.language}

$criteria

DIFF (only changed regions, with a few context lines):
```diff
${request.unifiedDiff}
```$contextSection

SCOPE — THIS IS CRITICAL:
- Review ONLY the lines that were ADDED or MODIFIED in this PR. These are the lines starting with "+".
  Each "+" line is annotated with its real line number like:  +someCode    [line 42]
- Lines starting with " " (space) are unchanged context — use them ONLY to understand the change.
  DO NOT report issues that exist purely in unchanged context lines.
- Lines starting with "-" are removed — do not review them.
- If a "+" line is fine, say nothing about it. Do not invent issues to fill the response.

SEVERITY RUBRIC — assign severity STRICTLY by IMPACT × LIKELIHOOD. Do not guess.
Decide by asking: "If this ships, what is the worst realistic outcome, and how likely is it?"

- Critical: Will almost certainly cause a serious failure in production.
    e.g. guaranteed NullReferenceException on a common path, SQL injection, secret leaked,
    data loss/corruption, auth bypass, deadlock that hangs requests, infinite loop.
- High: Likely to cause a real bug, security hole, or outage under realistic conditions.
    e.g. unhandled exception on a plausible input, .Result/.Wait() that can deadlock,
    N+1 query on a hot path, race condition, missing authorization check, resource leak
    (undisposed connection/stream), wrong business logic that returns incorrect results.
- Medium: Real problem but bounded impact or needs specific conditions.
    e.g. missing CancellationToken, swallowed exception, EF tracking on read-only query,
    edge-case bug, moderate performance waste, SOLID/DDD violation that hurts maintainability.
- Low: Minor correctness or design smell; unlikely to bite soon.
    e.g. magic number, naming, slightly long method, minor duplication, missing guard clause
    on an internal method.
- Info: Purely informational / nitpick / optional improvement. No correctness or risk impact.

CALIBRATION RULES (apply these to avoid under/over-rating):
- A potential crash (null deref, unhandled exception, index-out-of-range) on a realistic path
  is NEVER below High.
- Anything in the Security category that is exploitable is Critical or High — never Low/Info.
- Data loss, auth, injection → Critical.
- Pure style/naming/formatting → Low or Info, never Medium+.
- Be consistent: two issues with the same real-world impact MUST get the same severity.

INSTRUCTIONS:
- Only report real, objective, verifiable issues introduced by the "+" lines.
- Be thorough but precise: do NOT invent issues, and do NOT miss real bugs. If unsure whether
  something is a real problem, only include it if you can state a concrete failure scenario.
- For "line", use the [line N] number shown on the relevant "+" line.
- Explain WHY the issue is a problem and the concrete scenario in which it fails.
- Provide a concrete corrected code snippet in the suggestion field.
- Order findings by severity (Critical first).
- If the changed lines have no issues, return an empty array [].
- Respond with ONLY a valid JSON array. No markdown fences, no prose outside the JSON.

FIELDS — "comment" and "suggestion" are for an internal details panel (can be direct/technical).
"friendlyComment" is what gets POSTED on the pull request, so make it sound like a real, warm teammate:
- Write it as a complete, ready-to-post PR comment in first person.
- Genuinely friendly and human — vary your openings (e.g. "Nice work here! One small thing —",
  "Hey, I noticed…", "Quick thought on this —", "Looks good! Though I wonder if…",
  "Just spotted something worth a look:"). DO NOT start every comment the same way.
- Be encouraging and collaborative, never blunt or robotic. Soften with "maybe", "what do you think",
  "might be worth", "could we".
- Explain the concern briefly and, if helpful, mention the fix in plain words.
- NEVER mention AI, automated review, tools, "Azure", severity, or category labels.
- Keep it natural and concise — 1-3 sentences, like a kind senior engineer leaving a note.

REQUIRED JSON FORMAT (array, even for single items):
[
  {
    "file": "${request.filePath}",
    "line": <first affected line number (from the [line N] annotation)>,
    "endLine": <last affected line number; same as line if single-line>,
    "severity": "<Critical|High|Medium|Low|Info>",
    "category": "<Bug|Security|Performance|CleanCode|Solid|DDD|Maintainability|MissingTests|Concurrency>",
    "comment": "<technical: why the issue exists and what can go wrong>",
    "suggestion": "<technical: plain-English explanation of HOW to fix it>",
    "originalCode": "<the EXACT existing code snippet that should be replaced — copy it verbatim from the + lines, character-for-character including indentation>",
    "suggestedCode": "<the corrected code that should replace originalCode, with the SAME scope>",
    "friendlyComment": "<a warm, varied, ready-to-post PR comment — see FIELDS guidance above>"
  }
]

CRITICAL RULES FOR "originalCode" and "suggestedCode" (an auto-fix tool uses these):
- "originalCode" MUST be an exact, verbatim copy of existing code from the "+" lines —
  same text, same indentation, same number of lines. It will be string-matched in the file.
- "suggestedCode" replaces EXACTLY that snippet and nothing more. Same surrounding scope.
- They must cover the SAME region. Do NOT put a whole method in suggestedCode if originalCode
  is one line. Do NOT include extra unchanged lines in either field.
- If you cannot identify a precise verbatim snippet to replace, leave BOTH fields empty ("").
- Never duplicate existing code. The replacement is a 1:1 swap of originalCode → suggestedCode.
        """.trimIndent()
    }

    private val CSHARP_CRITERIA = """
REVIEW CRITERIA — apply this senior .NET checklist to the changed lines:

NULL & TYPE SAFETY
- Dereferencing a possibly-null reference (no null check / no ?. / no null-forgiving justified).
- Ignoring nullable annotations; using ! to silence the compiler without proof it's safe.
- Unsafe casts ((Foo)obj) where 'as' + null check or pattern matching is safer.

ASYNC / AWAIT
- async void methods (except event handlers) — should be async Task.
- Blocking on async: .Result, .Wait(), .GetAwaiter().GetResult() → deadlock / thread-starvation risk.
- Missing await (fire-and-forget Task not awaited or not intentionally discarded).
- Missing CancellationToken propagation through async call chains.
- await inside a loop where Task.WhenAll would be correct.

EF CORE / DATA
- N+1 queries: lazy navigation accessed in a loop; missing .Include().
- Loading whole entities when a projection (.Select) is enough.
- Missing AsNoTracking() for read-only queries.
- Building queries that can't translate to SQL (client-side evaluation).
- SaveChanges in a loop instead of a single batched call.

DI & LIFETIME
- Captive dependency: injecting a scoped service into a singleton.
- new-ing up a dependency that should be injected.
- IDisposable not disposed (missing using / await using).

EXCEPTIONS & VALIDATION
- catch (Exception) that swallows or rethrows incorrectly (losing stack trace via 'throw ex').
- Missing guard clauses / argument validation on public methods.
- Throwing generic Exception instead of a specific type.

SECURITY
- SQL built by string concatenation (injection) instead of parameters.
- Secrets/connection strings hardcoded.
- Missing authorization check on an endpoint/handler.
- Unvalidated input flowing into a sensitive sink.

DDD / CLEAN ARCHITECTURE
- Domain logic leaking into controllers / application / infrastructure layers.
- Anemic domain model (public setters everywhere, no invariants).
- Entities exposing mutable collections directly.
- Application layer depending on infrastructure concretions instead of abstractions.

SOLID & DESIGN
- SRP: a method/class doing too many things.
- DIP: depending on a concrete class where an interface is expected.
- Primitive obsession where a value object would enforce invariants.

PERFORMANCE & CLEAN CODE
- Unnecessary allocations / LINQ in hot paths; multiple enumeration of IEnumerable.
- Magic numbers/strings, deep nesting (>3), methods > ~30 lines, poor naming.
- Duplicated logic that should be extracted.
""".trim()

    private val GENERIC_CRITERIA = """
REVIEW CRITERIA — check the changed lines for:
1. Bugs: null refs, off-by-one, unhandled exceptions, incorrect logic.
2. Security: injection, secrets, missing auth, unvalidated input.
3. Concurrency: race conditions, improper async, missing cancellation.
4. Performance: needless allocations, blocking calls, N+1 patterns.
5. SOLID / design: SRP, DIP, leaky abstractions.
6. Clean code: magic values, deep nesting, long methods, poor naming, duplication.
7. Missing tests / guard clauses.
""".trim()
}
