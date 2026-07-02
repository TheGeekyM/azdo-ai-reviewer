package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.domain.WorkItem

object PromptBuilder {

    /**
     * Asks the model to check whether a PR's diffs actually satisfy a linked work item's
     * requirements (description / repro steps / acceptance criteria) — a QA-style check, not a
     * code-quality review.
     */
    fun buildValidateWorkItem(
        workItem: WorkItem,
        prTitle: String,
        prDescription: String,
        files: List<PrFile>
    ): String {
        val filesBlock = files.joinToString("\n\n") { f ->
            """
FILE: ${f.path}
```diff
${f.unifiedDiff}
```
""".trim()
        }

        return """
You are a meticulous QA-minded senior engineer. Validate whether this pull request actually
implements the work item below. Judge REQUIREMENTS COVERAGE only — not code quality, style, or
bugs (a separate review handles that).

WORK ITEM #${workItem.id} (${workItem.type})${if (workItem.state.isNotBlank()) " — ${workItem.state}" else ""}
Title: ${workItem.title}

Description:
${workItem.description.ifBlank { "(none)" }}
${if (workItem.reproSteps.isNotBlank()) "\nRepro Steps (bug):\n${workItem.reproSteps}\n" else ""}
${if (workItem.acceptanceCriteria.isNotBlank()) "\nAcceptance Criteria:\n${workItem.acceptanceCriteria}\n" else ""}

PULL REQUEST
Title: $prTitle
Description: ${prDescription.ifBlank { "(none)" }}

CHANGED FILES (diff — "+" lines are what the PR actually does):
$filesBlock

Break the work item into its individual checkable requirements (each acceptance-criteria bullet;
for a bug, each repro step / expected-vs-actual behavior; for a story/task with no AC, the key
asks in the description). For EACH one, decide if the diff evidence shows it was addressed.
Be concrete — cite what the diff does or doesn't do. If you cannot tell from the diffs shown,
say so rather than guessing.

For EACH finding, also point to the most relevant file/line from the diffs above (copy the file
path exactly from a FILE: header, and the line number from its [line N] annotation) — for Met,
where it was implemented; for Not Met/Partial, where it should have been but wasn't, or the
closest relevant code. Leave file/line blank ("" / 0) only if genuinely not identifiable.

For Not Met and Partial findings ONLY, also write a "friendlyComment": a warm, human, first-person
PR comment (varied opening, no AI/tool/Azure/severity mentions) explaining what's missing and
gently asking the author to address it — ready to post as-is. Leave it blank for Met findings.

Respond with ONLY a JSON object, no markdown fences, no prose:
{
  "verdict": "Meets requirements|Partially meets requirements|Does not meet requirements|Unclear",
  "findings": [
    {
      "requirement": "<short paraphrase>",
      "status": "Met|Not Met|Partial|Unclear",
      "explanation": "<why, referencing the diff>",
      "file": "<exact file path from a FILE: header, or \"\">",
      "line": <line number from a [line N] annotation, or 0>,
      "friendlyComment": "<warm first-person PR comment; blank if status is Met>"
    }
  ],
  "summary": "<2-4 sentence overall verdict in plain language>"
}
        """.trimIndent()
    }

    /** Single-file review (used for the ping/quick path). */
    fun build(request: FileReviewRequest): String {
        val isCSharp = request.language == "csharp"
        val persona  = if (isCSharp) CSHARP_PERSONA else genericPersona(request.language)
        val criteria = if (isCSharp) CSHARP_CRITERIA else GENERIC_CRITERIA
        val fullFile = request.fullFileContent?.take(12000) ?: "(not available)"

        return """
$persona

Review the change for file: ${request.filePath}

$criteria

DIFF (changed lines only, with context):
```diff
${request.unifiedDiff}
```

FULL FILE (reference only — understand how changed lines fit; resolve symbols/calls):
```${request.language}
$fullFile
```

$REVIEW_DISCIPLINE

$SEVERITY_AND_OUTPUT
        """.trimIndent()
    }

    /**
     * Whole-PR prompt: all changed files presented TOGETHER with the PR intent, so the model can
     * use surrounding code and cross-file references to judge whether each change is actually
     * correct — instead of speculating about isolated lines. This is the high-quality path.
     */
    fun buildPr(request: PrReviewRequest): String {
        val isCSharp = request.language == "csharp"
        val persona  = if (isCSharp) CSHARP_PERSONA else genericPersona(request.language)
        val criteria = if (isCSharp) CSHARP_CRITERIA else GENERIC_CRITERIA

        val filesBlock = request.files.joinToString("\n\n") { f ->
            val content = f.fullFileContent?.take(10000) ?: "(not available)"
            """
========================================================================
FILE: ${f.path}
------------------------------------------------------------------------
CHANGED LINES (diff — review only the "+" lines, each annotated [line N]):
```diff
${f.unifiedDiff}
```

FULL FILE (reference only — to understand the change and resolve symbols/calls):
```${request.language}
$content
```
""".trim()
        }

        val desc = request.prDescription.ifBlank { "(no description provided)" }

        return """
$persona

You are reviewing a COMPLETE pull request. Below are ALL of its changed files together.
Use the WHOLE picture: a method changed in one file may be called or defined in another file
shown here. Resolve references across these files before judging a change. First understand the
INTENT of the PR, then decide whether each change is correct, safe, and high quality.

PULL REQUEST
Title: ${request.prTitle}
Description:
$desc

$criteria

$filesBlock

========================================================================

$REVIEW_DISCIPLINE

Additional, because you can see ALL files:
- If a concern depends on code visible in ANY file above (a null check, validation, disposal,
  auth, or a method's real implementation), CHECK it before reporting. If the surrounding code
  already handles it, do NOT report it.
- A symbol used in one changed file may be defined in another changed file shown here — resolve
  it rather than assuming it's missing or wrong.

$SEVERITY_AND_OUTPUT_MULTIFILE
        """.trimIndent()
    }

    /**
     * Adversarial second pass: shows a skeptical reviewer the findings the FIRST pass produced,
     * plus the actual diff evidence, and asks it to confirm/downgrade/reject each one. This is
     * what catches the false positives and inflated severities a single pass tends to produce.
     */
    fun buildVerify(prTitle: String, findings: List<ReviewComment>, filesByPath: Map<String, PrFile>): String {
        val findingsBlock = findings.mapIndexed { i, c ->
            """
[$i] file=${c.file} line=${c.lineRange} severity=${c.severity.label} category=${c.category.label}
Claim: ${c.comment}
Original code:
${c.originalCode.ifBlank { "(not captured)" }}
Suggested fix:
${c.suggestedCode.ifBlank { "(none)" }}
""".trim()
        }.joinToString("\n\n")

        val diffsBlock = findings.map { it.file }.distinct().mapNotNull { filesByPath[it] }.joinToString("\n\n") { f ->
            """
FILE: ${f.path}
```diff
${f.unifiedDiff}
```
""".trim()
        }

        return """
You are a skeptical senior engineer doing a SECOND, ADVERSARIAL pass over another reviewer's
findings for the pull request "$prTitle". Your job is only to catch false positives and wrong
severities in the findings below — do NOT look for new issues.

For EACH numbered finding, decide:
- "confirm": the claim is real, concretely wrong, and the severity is right.
- "downgrade": real, but the severity is too high — give the corrected severity.
- "reject": not a real issue — speculative, already handled by surrounding code, functionally
  identical to the original, or simply wrong. Give a one-sentence reason.

Be harsh: if you cannot back a finding with the diff evidence below, reject it.

FINDINGS:
$findingsBlock

DIFF EVIDENCE:
$diffsBlock

Respond with ONLY a JSON array, no markdown fences, no prose:
[{"index": <n>, "verdict": "confirm|downgrade|reject", "severity": "<only if downgrade>", "reason": "<short>"}]
        """.trimIndent()
    }

    // ── Personas ───────────────────────────────────────────────────────────────
    private fun genericPersona(language: String) =
        "You are a principal-level software engineer and meticulous code reviewer for $language."

    private val CSHARP_PERSONA = """
You are a principal-level C# / .NET 9 engineer with 15+ years of production experience, doing a
THOROUGH review — go deep, not just surface-level null checks. You have deep, battle-tested
expertise in:
- ASP.NET Core, Entity Framework Core (EF Core), LINQ-to-SQL translation rules
- async/await, TPL, thread safety, CancellationToken propagation
- Nullable reference types, null safety patterns
- Dependency Injection, object lifetimes, IDisposable
- Domain-Driven Design, Clean Architecture, CQRS/MediatR
- ABP Framework AND ASP.NET Zero conventions specifically: application services, DTOs/AutoMapper,
  repository pattern, UnitOfWork, auditing, soft delete, multi-tenancy, permission-based authorization,
  localization
- SQL Server / database performance: indexes, execution plans, query shape, pagination
- Security: OWASP Top 10, input validation, authorization
- Encapsulation and API surface hygiene: visibility, dead code, unused imports

IMPORTANT — DO NOT report EF Core query *translatability* issues (whether a method/expression
translates to SQL). A Roslyn static analyzer runs separately and owns those checks with
compiler-level accuracy. If you guess at translatability you will produce false positives.
You SHOULD still flag EF *design* problems (N+1, over-fetching, missing AsNoTracking on
read-only queries, SaveChanges in a loop) — those are judgment calls the analyzer doesn't make.
""".trim()

    // ── Criteria ───────────────────────────────────────────────────────────────
    private val CSHARP_CRITERIA = """
REVIEW CRITERIA

NULL & TYPE SAFETY
- Dereferencing a possibly-null reference without null check / ?. / pattern match
- Using ! operator without a provable non-null guarantee
- Unsafe cast (Foo)obj where as + null check or pattern matching is safer
- .Value on a Nullable<T> without .HasValue guard

ASYNC / AWAIT
- async void (except event handlers)
- Blocking on async: .Result, .Wait(), .GetAwaiter().GetResult() — deadlock/starvation risk
- Missing await on a Task (unintentional fire-and-forget)
- Missing CancellationToken where the method signature supports it
- await inside a loop where Task.WhenAll is correct
- Captured closure values across await boundaries on thread-unsafe objects

EF CORE — DESIGN ONLY (translatability is handled by the analyzer; do NOT report it):
- N+1: lazy navigation property accessed inside a loop; missing .Include() or .Select()
- Loading full entities when a .Select() projection suffices (over-fetching)
- Missing .AsNoTracking() on read-only queries
- .SaveChanges()/.SaveChangesAsync() inside a loop instead of a single batched call
- Materializing the query too early (ToList() before further filtering)

DI & LIFETIME
- Captive dependency: scoped service injected into singleton
- Transient IDisposable not disposed (missing using/await using)
- new-ing a dependency that should be resolved from DI container

EXCEPTIONS & VALIDATION
- catch(Exception e) { throw e; } — loses stack trace; must be throw;
- Swallowed exception: catch block that logs nothing and continues silently
- Missing guard clauses / ArgumentNullException on public API entry points
- Throwing new Exception() instead of a domain-specific/framework exception type

UNITOFWORK / TRANSACTION (avoid this false-reasoning pattern):
- NOT calling Complete() on an exception path is CORRECT — the UoW should roll back on failure.
  Never flag this. Only flag if Complete() is missing on the SUCCESS path, or CompleteAsync()
  should be used instead of Complete() in an async method.

SECURITY
- SQL built by string concatenation → injection risk
- Hardcoded secrets, connection strings, API keys in source
- Missing [Authorize] / permission check on an endpoint or handler
- Unvalidated user input flowing into file paths, shell commands, or external URIs
- Sensitive data (passwords, tokens, PII) written to logs

DDD / CLEAN ARCHITECTURE
- Domain logic (invariants, business rules) implemented in Application or Infrastructure layer
- Anemic domain model: entities with only public setters, no encapsulation
- Application service depending on an Infrastructure concretion instead of an abstraction
- Value objects implemented as plain primitives (primitive obsession)

ASP.NET ZERO / ABP FRAMEWORK — only flag when the surrounding code makes it unambiguous:
- Application service method bypasses the repository pattern (raw DbContext instead of
  IRepository<TEntity>/IRepository<TEntity, TKey>) where the rest of the codebase uses repositories
- Entity/DTO manually sets CreationTime/CreatorUserId/LastModificationTime on an
  IAudited/IFullAudited/ICreationAudited entity — the framework sets these automatically; a manual
  override is a bug, not just style
- Hard-deleting (Repository.Delete / raw SQL DELETE) an entity that implements ISoftDelete instead
  of relying on the framework's soft-delete behavior
- Multi-tenant entity (implements IMayHaveTenant/IMustHaveTenant) queried/created without going
  through the normal DbContext/repository path that applies the tenant filter — risks cross-tenant
  data leakage
- New application service endpoint or handler with no permission check
  ([AbpAuthorize("Permission.Name")] or equivalent) where sibling endpoints in the same service have one
- Entity directly returned/bound from an application service instead of mapping to/from a DTO
  (AutoMapper CreateMap or manual mapping) — risk of over-posting or leaking internal fields
- User-facing string hardcoded in English where the surrounding code uses L("Key")/localization
  resources for equivalent strings
- Long-running or expensive work done inline in a request instead of via IBackgroundJobManager,
  when the codebase already uses background jobs for similar work

DATABASE & CODE PERFORMANCE
- Missing index-friendly filtering: WHERE/OrderBy on a column that isn't indexed when an indexed
  alternative is available and obvious from context (do not guess at actual index existence if it
  cannot be seen — only flag when the change clearly regresses an existing indexed access pattern)
- Multiple enumeration of an IEnumerable<T> (iterating twice, ToList() in a loop)
- Unnecessary allocations in hot paths (string concatenation in loops, boxing, unneeded LINQ chains)
- Large collections pulled into memory for in-memory filtering that could be a SQL WHERE
- Missing pagination (Skip/Take) on a query that can return an unbounded result set to a client
- Synchronous blocking I/O (file/db/http) on a request-handling path where async is available

SOLID & DESIGN
- SRP: method/class with more than one clear responsibility
- DIP: depending on a concrete class where an interface is expected
- Magic numbers/strings that should be named constants or enum values
- Method > ~40 lines doing multiple distinct things; deep nesting (>3 levels)

DEAD CODE, UNUSED IMPORTS & VISIBILITY — precision matters here; false positives are easy:
- Unused `using` directive: ONLY flag if no type/extension method from that namespace is referenced
  anywhere in the full file shown. If in doubt (e.g. it could be an extension-method or global-usings
  provider), SKIP.
- Unreachable code: statements after an unconditional return/throw/continue/break in the same block
- Private method/field/property added or touched by this change that is never referenced anywhere in
  the full file shown. SKIP if it could be used via reflection, DI, serialization, an interface
  contract, or a partial class/file not shown here.
- A member marked `public` that is never used outside its declaring class/file as shown, when it
  could reasonably be `private`/`internal`/`protected` instead (encapsulation) — do NOT flag public
  members that are clearly part of an interface implementation, a controller/application-service
  action, or a DTO.

READABILITY
- Misleading name: name implies X but code does Y
- Abbreviated names (usr, req, tmp) in non-trivial scope
- Inconsistency with surrounding codebase naming conventions
- Deeply nested ternaries or boolean expressions that should be extracted into a named variable/method
- Long parameter list (5+) that should be a parameter object/DTO
""".trim()

    private val GENERIC_CRITERIA = """
REVIEW CRITERIA — check the changed lines for:
1. Bugs: null refs, off-by-one, unhandled exceptions, incorrect logic.
2. Security: injection, secrets, missing auth, unvalidated input.
3. Concurrency: race conditions, improper async, missing cancellation.
4. Performance: needless allocations, blocking calls, N+1 patterns, missing pagination.
5. SOLID / design: SRP, DIP, leaky abstractions.
6. Clean code & readability: magic values, deep nesting, long methods, poor naming, duplication.
7. Dead code & visibility: unused imports (only if unambiguous), unreachable code, unused
   private members, members that could be narrower in scope than they are.
8. Missing tests / guard clauses.
""".trim()

    // ── Shared discipline + output ─────────────────────────────────────────────
    private val REVIEW_DISCIPLINE = """
SCOPE & DISCIPLINE — CRITICAL (wrong comments destroy trust; precision beats coverage):
- Review ONLY lines starting with "+" (added/modified). Each is annotated [line N].
- Lines starting with " " are unchanged context (use only to understand); "-" lines are removed (ignore).
- Before reporting ANY issue, pass this gate — if it fails, STAY SILENT:
  1. Is it introduced by a "+" line (not pre-existing context)?
  2. Can I name a CONCRETE, realistic failure scenario with the exact mechanism?
     If I'm speculating or hand-waving → SKIP.
  3. Does the surrounding code I can see already handle it (null check, validation, auth, disposal)?
     If yes → SKIP.
  4. Is my suggested fix actually different in BEHAVIOR from the original? If functionally
     identical → the original is fine → SKIP.
  5. Would a principal engineer genuinely raise this in a real PR review? If not → SKIP.
- Prefer reporting FEWER, certain issues over many speculative ones. An empty result is acceptable
  and correct when the changes are fine.
""".trim()

    private val SEVERITY_BLOCK = """
SEVERITY — assign by IMPACT × LIKELIHOOD:
- Critical: almost certainly serious prod failure (guaranteed null-deref on common path, SQL
  injection, secret leaked, data loss/corruption, auth bypass, deadlock).
- High: likely real bug/security/outage (unhandled exception on plausible input, .Result/.Wait()
  deadlock, N+1 on hot path, race condition, missing auth check, resource leak).
- Medium: real but bounded (missing CancellationToken, swallowed exception, edge-case bug,
  moderate perf waste, DDD/SOLID violation hurting maintainability).
- Low: minor smell (magic number, naming, slightly long method, minor duplication).
- Info: optional improvement, zero risk.

CALIBRATION: realistic crash → never below High; exploitable security → Critical/High, never Low/Info;
data loss/auth/injection → Critical; pure style/naming → Low/Info, never Medium+; identical-impact
issues get identical severity.
""".trim()

    private val SEVERITY_AND_OUTPUT = """
$SEVERITY_BLOCK

OUTPUT — respond with ONLY a valid JSON array, no markdown fences, no prose:
[
  {
    "line": <number from [line N] annotation>,
    "endLine": <last affected line; same as line if single-line>,
    "severity": "Critical|High|Medium|Low|Info",
    "category": "<Null Safety|Async/Await|EF Core|DI Lifetime|Exception Handling|Security|DDD|Performance|SOLID|Naming|Clean Code>",
    "title": "<10 words max>",
    "comment": "<technical: what is wrong and the concrete failure scenario>",
    "originalCode": "<exact current snippet, 1–5 lines, verbatim>",
    "suggestedCode": "<corrected replacement, same scope as originalCode>",
    "friendlyComment": "<warm, human, first-person PR comment, varied opening>"
  }
]
If no real issues, return exactly: []
""".trim()

    private val SEVERITY_AND_OUTPUT_MULTIFILE = """
$SEVERITY_BLOCK

OUTPUT — respond with ONLY a valid JSON array, no markdown fences, no prose.
Each finding MUST include the "file" path it belongs to (copy it exactly from the FILE: headers above):
[
  {
    "file": "<exact file path from a FILE: header>",
    "line": <number from [line N] annotation in that file's diff>,
    "endLine": <last affected line; same as line if single-line>,
    "severity": "Critical|High|Medium|Low|Info",
    "category": "<Null Safety|Async/Await|EF Core|DI Lifetime|Exception Handling|Security|DDD|Performance|SOLID|Naming|Clean Code>",
    "title": "<10 words max>",
    "comment": "<technical: what is wrong and the concrete failure scenario>",
    "originalCode": "<exact current snippet, 1–5 lines, verbatim>",
    "suggestedCode": "<corrected replacement, same scope as originalCode>",
    "friendlyComment": "<warm, human, first-person PR comment, varied opening>"
  }
]
If no real issues across all files, return exactly: []
""".trim()
}
