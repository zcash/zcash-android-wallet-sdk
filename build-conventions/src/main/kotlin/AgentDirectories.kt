/**
 * Directories that AI coding agents create inside a checkout for their own
 * state. Some of them hold entire nested git worktrees of other branches: a
 * directory under `.claude/worktrees` is a full second checkout, `.git` and
 * all.
 *
 * Static analysis walks the project directory, so without these exclusions a
 * worktree left behind by an agent is analysed as if it were part of this
 * branch, and reports failures for code that is not yours. Every directory
 * here is tool state that is gitignored and never part of the build, so
 * excluding it can only remove noise.
 *
 * The list is deliberately generous. These are exclusions, so a name that
 * never appears on disk costs nothing, while a missing name reintroduces the
 * bug. Add to it rather than trimming it.
 */
val AGENT_DIRECTORIES =
    listOf(
        ".aider",
        ".amp",
        ".augment",
        ".cagent",
        ".claude",
        ".cline",
        ".clinerules",
        ".codex",
        ".continue",
        ".crush",
        ".cursor",
        ".devin",
        ".gemini",
        ".goose",
        ".junie",
        ".kiro",
        ".opencode",
        ".qodo",
        ".roo",
        ".trae",
        ".windsurf"
    )

/** Ant-style patterns matching everything under [AGENT_DIRECTORIES]. */
val AGENT_DIRECTORY_EXCLUDES = AGENT_DIRECTORIES.map { "**/$it/**" }
