
## the purpose of this research is to find out ways for utilizing mutiple tasks with main agents and isolated by all working hand in hand and also ways to improve DSH which is a great tool of times in this agentic era

The DeepSeek Harness does not include a built-in "critic agent" by default, but its plugin architecture allows users to integrate or create specialized presets that act as critics or advisors.

A prominent community-created preset is Ciel (by user higekibaka), which functions as a pre-planning advisor and convergent critic.  Ciel provides a second, knowledge-rich layer that offers directions, prior art, pitfalls, and verification checklists without executing steps. 

To use such a critic agent:

Users install the preset as a plugin or profile within the DeepSeek Harness environment.
The critic agent operates by analyzing the primary agent's plan or output, offering convergent feedback before final execution.
This aligns with the framework's "everything is a plugin" philosophy, enabling users to compose custom agentic workflows with dedicated review roles. 
Other users have also created custom patch layers and presets for verification, such as Learner-Preset (by JingMox) for knowledge components, or custom git worktree and session cost plugins to enhance oversight. 


deepseek harness critic agent preset github

View all

Copy

🌐
🌐
🌐
🌐
+16
how does ciel get used? does the model auto-use or we need to tell it?


Found information on Critic Agent preset usage
Based on what's available, here's what I can tell you:

Ciel (夏尔) is a preset/profile plugin — it's listed under "Profiles & Patch Layers" in the awesome-deepseek-harness index, not under a tool or auto-inject category.  That means you select it explicitly as your agent preset for a session; it doesn't auto-fire in the background like, say, dsh-advisor (which is described as "second model passively reviews each turn and injects notes"). 

In practice, the workflow would be:

Install the plugin:
dsh plugin --profile web add "github:higekibaka/dsh-ciel"

Restart the web app. 
Create a new session and pick the Ciel preset from the agent preset picker (it will appear alongside Standard / Code / Minimal / Creator). 
Once selected, Ciel acts as the session's primary agent persona — a "knowledge-rich model" that gives directions, prior art, pitfalls, and verification checklists but deliberately stops short of writing execution steps.  You'd then hand off to a different preset (e.g. Standard) for actual implementation.

I couldn't find the full README for the repo, so if it also registers a subagent or skill that the main agent can invoke mid-session (rather than only as a standalone preset), that detail isn't in the index. Check the repo's README for any /ciel slash command or subagent tool registration.


higekibaka dsh-ciel github

View all

Copy


more about in-session parallel agents usage


Researched parallel agent execution in DeepSeek Harness
In DSH, parallel in-session agents work at a few layers:

Native subagent tools (auto-used by the model)

The subagent and subagent_fork tools are registered in the agent's tool set, so the model decides when to spawn them — you don't need to explicitly tell it. It can issue multiple subagent calls in a single turn, and they run in parallel. The parent blocks until all children return their results (one-shot mode) or gets a job handle (continuable mode). 

Two delegation modes:

Mode	Lifecycle	Use case
One-shot	Single turn, returns a final result	Focused side tasks (search, review, generate)
Continuable	Durable session, persists across turns	Multi-turn follow-ups, long-running work

Concurrency control

maxParallelToolCalls (default 10) caps how many parallel-safe tool calls are in flight per step.  Subagent dispatches count against this.

Plugins that extend parallel agent usage

dsh-routed-subagent — lets any session dispatch a subagent fully mounted on a different preset (e.g. Ciel) with per-call model override.  Background by default: returns a job id immediately, the conversation stays free to dispatch more children in parallel.
dsh-subagent-registry — registers ~/.dsh/agents/*.md files as named subagents callable via a use_agent tool.  The main model picks by name from the roster. Supports background: true for interactive subagents you can follow up with ask_agent.
dsh-agent-teams — "captain" pattern: the current session spawns resumable member agents, breaks goals into a dependency DAG, and coordinates via a shared scheduler.  Members are real DSH sub-agents, not one-off scripts.
dsh-crew — reverse direction: lets external orchestrators (Claude Code, Codex, Grok) fan out parallel workers into DSH sessions. 
Practical pattern with Ciel

With dsh-routed-subagent, the main agent (running Standard preset) can call:

subagent_routed(preset: "ciel", task: "review this plan for pitfalls")

The child mounts Ciel's full composition (persona, skills, tools) and returns its advisory. You can fire several in parallel — e.g. one Ciel for architecture review, one Standard for implementation — and collect results when they complete. 

So to directly answer: the model auto-uses subagents (they're just tools it can call), and parallelism is a natural consequence of issuing multiple calls in one turn.  You don't need to explicitly say "run in parallel" — the model does it when the task warrants fan-out. 


dsh-routed-subagent dsh-agent-teams dsh-crew documentation

View all

Copy


more tips to unlocking dsh capabilities



Searched for advanced DeepSeek Harness tips.
Here's a practical rundown of the less-obvious levers that change what DSH can actually do for you:

Composition & Introspection
dsh web --dump-config — prints the full composed plugin tree for the active profile.  This is the single most useful diagnostic: it shows exactly which model, tools, skills, sandbox, and storage plugins are mounted. If something's missing, it's in here.
dsh-xray — goes further: attributes every booted row to the layer that introduced it, diffs declared vs. actual trees (catches patch rows DSH silently skips), and exposes an xray_composition tool so the agent can introspect its own capability set mid-session. 
Profiles & patch layers — a profile stacks bundle patch layers, then your own cordis.patch.yml (profile-level → $DSH_HOME-level → --patch overlays).  A patch targets a row by id and replaces its whole config (no deep merge).  This is how you build task-specialized runtimes (long-horizon, math-reasoning, etc.) without forking. asdf17128/dshp lets you list, clone, diff, and share whole profiles as one portable file. 

dsh web dump-config dsh-xray profiles github

View all
Context & Cost Control
dsh-context — shows what your context window is actually made of: per-request history, compression/injection events, per-message token stats.  One confirmed bug had it reading both CLAUDE.md and AGENTS.md, doubling the system prompt. You can't fix what you can't see.
dsh-context-doctor — token cost of the AGENTS.md chain, skill catalog, and tool schemas, with duplicate/conflict detection and pruning tips. 
dsh-compressor — compresses tool output, cuts up to ~20% of context without affecting cache or agent performance. 
dsh-bash-rtk — routes bash commands through rtk (Rust Token Killer) to compress tool output at the executor level. 
dsh-tier-router — the single most effective cost lever: strong model plans, cheap model implements.  More effective than any prompt trimming.
dsh-cost-meter / dsh-meow-cachebilling — per-turn and per-session cost visibility, including cache hit/miss breakdowns. 

dsh-context dsh-context-doctor dsh-compressor dsh-bash-rtk dsh-tier-router dsh-cost-meter ds

View all
Skills (Custom Capabilities)
Skills are the cleanest way to teach DSH new procedures. Drop a <skill-name>/SKILL.md directory into the right path and restart:

Priority	Path
100	<projectRoot>/.dsh/skills
200	<projectRoot>/.agents/skills
400	~/.dsh/skills
500	~/.agents/skills

Project-level skills override user-level ones. The model sees skill summaries in its catalog and loads the full body on demand via the skill tool. For distribution, wrap a skill in a plugin and register via ctx.skills.register(). 

dsh-skill-mover scans 14 other agent platforms (Cursor, Claude Code, Codex, Hermes, etc.) and migrates their skills into DSH with dedup and rollback.  dsh-movein does the same for full setups (skills, commands, agents, MCP servers). 

Memory & Persistence
dsh-mnemon — cross-session memory vault. 
plur-ai/dsh-plugin (PLUR) — persistent memory where "engrams" are rendered into the system prompt on each assembly rather than sitting behind a tool call, so recall doesn't require the model to remember to look.
dsh-session-sync — cross-device session sync via a dedicated git mirror with append-only conflict resolution. 
dsh-pin-recall — pin assistant replies and recall them into the next model turn (/pin, /recall). 
Action & Perception Extensions
dsh-browser — lets DSH work inside your existing Chrome session (authenticated web tasks). 
ModLens / dsh-vision-toolkit — give text-only DeepSeek models vision via structured OCR, layout analysis, and multi-image Q&A. 
dsh_workflow — turns one-off agent runs into reusable, persisted multi-step workflows. 
dsh-mcp-manager — MCP server manager with OAuth (PKCE) or static-token auth; tools registered as mcp_____*. 
dsh-mcp-skill-panel — real-time enable/disable for MCP servers and skills to free context; optional AI middle layer with state-based visibility filtering. 
Resilience & Safety
dsh-startup-guard — preflights plugin composition, smoke-tests host apply(), and quarantines crash-causing bundles so one broken plugin can't brick startup. 
dsh-poison-guard — pre-install supply-chain scan of plugin source. 
dsh-secret-redactor / secret-guard — masks or blocks credential-shaped strings before they reach the model. 
dsh-trajectory-governance — rebuilds flat session logs into branch trees, flags loop deadlock, invalid retry, and goal drift with cost attribution. 
Workflow Habits
Use Trajectory after every failed run — it shows what context was injected, which tool results came back, and where the agent went off-rails.  Forking a promising branch from trajectory is easier than replaying chat.
Install dsh-find-plugin early — lets the agent search the plugin ecosystem for you and return ranked recommendations with install commands. 
Install dshmarket before other plugins — gives you a graphical browser in Settings so you stop doing everything from the terminal. 
Separate eval from delivery — Minimal mode for benchmarks, Standard/Code for real tasks. 
Keep experimental plugins in a dedicated profile — prevents interference with your production web profile.
Pin versions — this is still a developer preview; breaking changes are expected.  Read changelogs before upgrading.
