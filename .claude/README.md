# `.claude/` - Claude Code project configuration

Added at the start of Phase 1 (the Phase 0 skill was written after Phase 0 was merged).

## Files

| File | Committed? | Purpose |
|---|---|---|
| `settings.json` | yes | Shared permissions for everyone working on this repo |
| `settings.local.json` | **no** (gitignored) | Personal overrides - here: allow reading the local `.env` |
| `skills/<name>/SKILL.md` | yes | Project skills: conventions Claude loads automatically when they fit the task |

## Permissions (`settings.json`)

JSON cannot hold comments, so the reasoning is here:

- **allow** - read-only git commands, `git add` / `git commit`, and the Docker / compose /
  curl commands used to build and test the stack. These run without a prompt.
- **deny** - everything that publishes or rewrites history or changes branches:
  `git push`, `git merge`, `git rebase`, `git reset --hard`, creating/deleting branches.
  The developer does these by hand (one branch per phase, PRs into `dev-branch`).
  Writing or editing `.env` is denied too - secrets are managed by hand only.
- Anything not listed asks for confirmation.
- `deny` always wins over `allow`, also over `settings.local.json`.

## Why reading `.env` is only in `settings.local.json`

Letting Claude read `.env` puts the secret values into its context. That is a personal
decision for one machine with local dev secrets, so it is not committed for everybody.

## Skills - one per phase

Each roadmap phase ends with a commit that adds or extends a skill capturing what the
phase established, so later work follows the same rules.

Skills are **project-agnostic** - they can be copied into any other project: part A = principles
that hold in any language, part B = the concrete Java 21 / Spring Boot microservices implementation
(starters, configuration, code patterns) with generic examples, part C = the equivalent tools in other
stacks. Nothing in a skill names this repo's services, files or commands; those live in `CLAUDE.md`,
the README and `docs/implementation-plans/`.

| Phase | Skill |
|---|---|
| 0 - fixes & foundation | `skills/service-conventions` - code style, service structure, API, DB, messaging, Docker |
| 1 - testing safety net | `skills/testing-conventions` - which test for which change, Testcontainers, fakes, naming, Maven setup, how to run |
| 2 - CI + Docker Hub | `skills/ci-conventions` - pipeline order, path filters, e2e in CI, immutable image tags, scanning, secrets, pinned actions, Dependabot |
| 3 - Spring Security + Auth0 | `skills/security-conventions` - registration/login for mobile and web (PKCE, social login), token storage, user profiles keyed by `sub`, account deletion, local token verification, defence in depth, permissions and roles, per-object rules (owner, followers, group admins), service-to-service tokens (HTTP + gRPC), 401/403, CORS, rate limits, testing with fake issuers |
