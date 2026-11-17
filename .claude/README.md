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

| Phase | Skill |
|---|---|
| 0 - fixes & foundation | `skills/service-conventions` - code style, service structure, API, DB, messaging, Docker |
| 1 - testing safety net | `skills/testing-conventions` - which test for which change, Testcontainers, fakes, naming, Maven setup, how to run |
