# Phase 2 — CI + Docker Hub

| | |
|---|---|
| Branch | `feature/phase-02-ci-docker-hub` (from `dev-branch` after PR #3) |
| Depends on | Phase 1 (the tests CI runs) |
| Status | 📝 plan - waiting for approval |
| Roadmap row | GitHub Actions per service (path filters) · build/test · images on Docker Hub tagged by git SHA · Dependabot · Trivy · Docker improvements |

## How to read this file

You should understand the whole phase from this file alone, without opening the code.
The **Technology map** says which tools are used and why. **Steps** says, for every step, *what* is done,
*why*, *how*, *when* you would use the same technique elsewhere, and *how it is verified*.
In the workflow YAML every job and every step gets a `# PHASE 2 (step 2.x)` comment.
**"What you need to do"** lists the things only you can do (accounts, tokens, GitHub settings).

## Goal

Today the tests from Phase 1 only run when someone remembers to start them, and images exist only on
the machine that built them. After this phase:

- every push and pull request runs the tests of **the services that changed**, on GitHub, automatically;
- pull requests also run the end-to-end tests against the full compose stack;
- after a merge into `dev-branch` or `master`, the images of the changed services are scanned for
  vulnerabilities and pushed to **Docker Hub**, tagged with the git commit, so Kubernetes (Phase 7) and AWS
  (Phase 8) can pull exactly the version that was tested;
- Dependabot opens pull requests when a library, base image or GitHub Action has an update.

### Out of scope (and where it goes instead)

| Not in this phase | Where |
|---|---|
| Deploying the images anywhere (CD) | Phase 7 (Helm, local cluster) and Phase 8 (AWS) |
| A minimum coverage % that fails the build | later, once security tests exist (Phase 3) - for now coverage is only reported |
| Security tests for Auth0 tokens | Phase 3 (a second testing round, written with the security code) |
| `docker-compose.yml` pulling images from Docker Hub instead of building | Phase 7 - compose keeps building locally |
| Signing images, SBOM attestations | not planned (could be an extra) |

## What you need to do (outside the repo)

Only you can do these. I will remind you at the step that needs each one.

| When | What | Where |
|---|---|---|
| **before step 2.3** | Have a Docker Hub account | hub.docker.com |
| **before step 2.3** | Create an **access token** with *Read & Write* scope (do not use your password in CI) | Docker Hub → Account settings → Personal access tokens → Generate |
| **before step 2.3** | Add 2 repository secrets: `DOCKERHUB_USERNAME` (your Docker Hub user name) and `DOCKERHUB_TOKEN` (the token) | GitHub repo → Settings → Secrets and variables → Actions → New repository secret |
| optional, before 2.3 | Docker Hub creates a **public** repository on the first push. If you want the images private, create the 5 repositories first (`pm-patient-service`, `pm-billing-service`, `pm-analytics-service`, `pm-auth-service`, `pm-api-gateway`); the free plan allows 1 private repository | Docker Hub → Repositories → Create |
| after the PR of this phase is merged | Protect `dev-branch`: require the CI checks to pass before merging | GitHub repo → Settings → Branches (or Rules → Rulesets) → add rule for `dev-branch` → "Require status checks to pass" |
| after step 2.4 is merged | Turn on Dependabot alerts and security updates (version-update PRs work from the config file alone) | GitHub repo → Settings → Code security |

Nothing else is needed: the end-to-end job generates throwaway database passwords and a JWT secret
inside the CI run, so no `.env` values go to GitHub.

## Technology map

| Technology | What it is | Why we use it here | Steps |
|---|---|---|---|
| **GitHub Actions** | CI service built into GitHub; workflows are YAML files in `.github/workflows/`, run on GitHub's Linux machines (*runners*) | The repo is on GitHub; free for this use | all |
| **Workflow / job / step** | A *workflow* reacts to events (push, PR); it has *jobs* (each on a fresh runner, can run in parallel); a job has *steps* (commands or reusable *actions*) | Structure of `ci.yml` | 2.1–2.3 |
| **`dorny/paths-filter`** | Action that tells which folders changed in a push/PR | Test and build **only the changed services**; a change in `proto/` counts for patient, billing and analytics | 2.1, 2.3 |
| **Job matrix** | Runs the same job once per value of a list, in parallel | One test job per changed service, no copy-pasted YAML | 2.1, 2.3 |
| **`actions/setup-java`** (Temurin 21, Maven cache) | Installs the JDK and caches `~/.m2` between runs | Same Java as the Dockerfiles; dependencies are not downloaded on every run | 2.1 |
| **Testcontainers on the runner** | GitHub's Ubuntu runners have Docker, so the Phase 1 containers start normally | The same tests as locally, no special CI setup | 2.1 |
| **JaCoCo** (0.8.16) | Java agent that measures which lines the tests executed; writes an HTML/XML report | See coverage per service; uses the `@{argLine}` hook prepared in Phase 1 | 2.1 |
| **`actions/upload-artifact`** | Stores files from a run (reports) for download | Test and coverage reports readable after a failed run | 2.1, 2.2 |
| **Docker Compose in CI** | The same `docker-compose.yml` as locally, with a generated `.env` | End-to-end tests against the real stack on every PR | 2.2 |
| **Docker Buildx + `docker/build-push-action`** | Builds images with BuildKit and pushes them | Fast builds with a layer cache stored in GitHub (`type=gha`) | 2.3 |
| **`docker/metadata-action`** | Computes image tags from git (commit SHA, branch) | Tag `sha-<commit>` = exactly what was tested; `dev` / `latest` as moving tags | 2.3 |
| **`docker/login-action`** | Logs in to a registry with the secrets | Push to Docker Hub without the password in the YAML | 2.3 |
| **Trivy** (`aquasecurity/trivy-action`) | Scanner for known vulnerabilities (CVEs) in OS packages and Java libraries inside an image | Do not publish an image with a critical, fixable vulnerability | 2.3 |
| **Spring Boot layered jars** (`-Djarmode=tools extract --layers`) | Splits the jar into layers: dependencies (change rarely) and application code (changes often) | Smaller pushes and pulls: usually only the small app layer changes | 2.3 |
| **Dependabot** | GitHub bot that opens PRs for new versions | Keeps Maven libraries, base images and Actions up to date; CI tests each PR | 2.4 |
| **Pinning actions to a commit SHA** | `uses: owner/action@<40-char sha> # v1.2.3` instead of `@v1` | A tag can be moved to malicious code; a commit SHA cannot. Dependabot still updates them | 2.1–2.3 |

### Versions (latest tags checked 2026-11-20; pinned to their commit SHA when used)

| Action / tool | Version |
|---|---|
| `actions/checkout` | v7.0.1 |
| `actions/setup-java` | v6.3.0 |
| `actions/upload-artifact` | v7.0.2 |
| `dorny/paths-filter` | v4.0.3 |
| `docker/setup-buildx-action` | v4.4.1 |
| `docker/login-action` | v4.8.0 |
| `docker/metadata-action` | v6.2.0 |
| `docker/build-push-action` | v7.4.0 |
| `aquasecurity/trivy-action` | v0.36.0 |
| JaCoCo Maven plugin | 0.8.16 |
| Runner | `ubuntu-24.04` (pinned, not `ubuntu-latest`) |

## Concepts you will learn in this phase

- **CI as the gatekeeper**: a PR is only merged when the checks are green - the tests from Phase 1 now protect `dev-branch` automatically.
- **Monorepo CI**: five independent services in one repo; path filters decide what to test and build.
- **Build once, tag immutably**: the image tagged `sha-<commit>` is the exact thing that passed the tests; later phases deploy that tag, never "latest".
- **Secrets in CI**: tokens live in GitHub's encrypted secrets, are masked in logs, and are not given to PRs from forks.
- **Supply-chain basics**: vulnerability scanning, pinned actions, automated updates.
- **Image layers**: why splitting the jar makes every push smaller.

## Steps

One commit per step. Every step is verified by a real run on GitHub (you push the branch; I read the
result with you) and, where possible, locally first.

### 2.1 CI: test the changed services

- **What:** `.github/workflows/ci.yml` with two jobs:
  - `changes`: which services changed (`proto/` -> patient, billing, analytics; the workflow file itself -> all);
  - `test`: a matrix with one job per changed service: `mvnw verify`, then upload the test and JaCoCo reports.
  Also: JaCoCo in the 5 POMs (report only), and `mvnw` made executable in git (today it is stored as `100644`,
  so `./mvnw` would fail on Linux).
- **Why:** tests that only run by hand get forgotten; the merge is where a broken change must be stopped.
- **How:**
  - Triggers: `pull_request` and `push` to `dev-branch` / `master`, and `workflow_dispatch` (start by hand).
  - `concurrency` cancels an older run of the same branch when you push again.
  - `permissions: contents: read` - the workflow gets the least rights it needs.
  - `setup-java` with `cache: maven`; Testcontainers uses the runner's Docker directly (no socket tricks needed).
  - JaCoCo's `prepare-agent` adds its agent through `@{argLine}`, next to the Mockito agent.
- **When:** in every repo with tests; path filters whenever several deployables share one repo.
- **Verified:** a PR touching only `billing-service/` runs only the billing job; a change in `proto/` runs
  three; a deliberately broken test makes the check red (then reverted).

### 2.2 CI: end-to-end tests against the compose stack

- **What:** job `e2e` in `ci.yml`, after `test`, for pull requests and pushes to `dev-branch` / `master`.
- **Why:** the per-service tests use fakes; only the real stack proves that the services fit together
  (gateway -> auth -> patient -> billing -> Kafka).
- **How:** generate a throwaway `.env` (random passwords and JWT secret with `openssl rand`), `docker compose up -d --build --wait`
  (waits until every healthcheck is healthy), run `Integration-tests` with `-Dgateway.url=http://localhost:4004`,
  on failure upload `docker compose logs`, always `docker compose down -v`.
- **When:** for the few critical journeys of a system of services; keep it to one job because it is the slowest.
- **Verified:** green on a PR; stopping billing in a test branch makes it red with the logs attached.

### 2.3 Images to Docker Hub (+ Trivy, layer cache, layered jars) — **needs your secrets**

- **What:** job `images` in `ci.yml`: for each changed service build the image, scan it, push it to
  `docker.io/<you>/pm-<service>`. Dockerfiles use Spring Boot's layered jar.
- **Why:** later phases (Kubernetes, AWS) deploy images from a registry, and they must be the tested ones.
- **How:**
  - Runs only on **push** to `dev-branch` / `master` (i.e. after a merge) and only when `test` and `e2e` passed.
    Pull requests build the image too but never push.
  - `metadata-action` tags: `sha-<short commit>` always, `dev` on `dev-branch`, `latest` on `master`.
  - `build-push-action` with `cache-from/to: type=gha` (BuildKit layer cache stored in GitHub).
  - Trivy scans the built image **before** the push; the job fails on `CRITICAL` vulnerabilities that have a
    fix (`ignore-unfixed: true`); `HIGH` ones are reported in the log.
  - Dockerfile runtime stage: `java -Djarmode=tools -jar app.jar extract --layers --launcher`, then one `COPY`
    per layer (dependencies, spring-boot-loader, snapshot-dependencies, application). The old lines stay
    commented out.
- **When:** for every service that is deployed from a registry.
- **Verified:** after merging into `dev-branch`, Docker Hub shows `sha-…` and `dev` tags; `docker pull` +
  `docker run` of one image starts; a second push of a small code change uploads only the app layer.

### 2.4 Dependabot

- **What:** `.github/dependabot.yml`: weekly updates for Maven (each of the 5 services + `Integration-tests`),
  Docker base images (each Dockerfile) and GitHub Actions; minor/patch updates grouped per service to keep
  the number of PRs small.
- **Why:** outdated libraries are the most common source of vulnerabilities; updates should arrive as PRs that
  CI tests automatically.
- **How:** one `updates:` entry per ecosystem and directory; `groups` for minor/patch; PRs target `dev-branch`.
- **When:** in every repo, from day one.
- **Verified:** after the merge, the Insights → Dependency graph → Dependabot tab lists the jobs; the first PRs run CI.

### 2.5 Docs

README "CI/CD" section (what runs when, badge, how to pull an image), CLAUDE.md, roadmap Progress, this file's
"What changed".

### 2.6 Phase 2 skill

`ci-conventions` (stack-agnostic): what a pipeline must check, path filters, immutable tags, secrets, pinned
actions, scanning, updates; B1 = GitHub Actions + Maven + Docker Hub as in this repo; B2 = GitLab CI / Jenkins
/ other registries.

## Verification (definition of done)

- [ ] A PR runs only the jobs of the changed services; all green
- [ ] A broken test turns the PR check red
- [ ] E2E tests run in CI against the compose stack, with logs attached on failure
- [ ] After a merge into `dev-branch`, Docker Hub has `sha-<commit>` and `dev` tags for the changed services
- [ ] Trivy runs before every push; no image with a fixable critical CVE is published
- [ ] No secret appears in the repo or in the logs
- [ ] Dependabot config is valid (shown in GitHub)
- [ ] CI runtime noted (budget for later phases)

## Exercises (for you, after the phase)

1. Open a PR that changes only `analytics-service/`. Which jobs run? Now touch `proto/patient_event.proto` (a comment). Which run now, and why?
2. Pull `sha-<commit>` of patient-service from Docker Hub and run `docker history` on it. Which layer changed between two commits?
3. Read one Trivy finding: which package, which version fixes it, and is it in our code or in the base image?
4. Why does the `images` job not run on pull requests from forks, and what would go wrong if it did?
5. Replace one `uses: …@<sha>` with `@v…` in a test branch: what is the risk you just took?

## Risks

| Risk | Mitigation |
|---|---|
| CI is slow (5 services × Testcontainers, E2E with full stack) | Only changed services run; Maven and BuildKit caches; jobs in parallel; E2E only once per run |
| Testcontainers behaves differently on the runner | The runner has a normal Docker daemon - simpler than our local socket setup; first run of 2.1 checks it |
| Secrets leak | Only 2 secrets, only used in the `images` job, which never runs for PRs; GitHub masks them in logs |
| Trivy blocks a merge because of a new CVE in the base image | Fail only on fixable CRITICAL; the fix is usually a base-image bump that Dependabot proposes |
| Many Dependabot PRs | Weekly schedule, minor/patch grouped per service |
| Free-plan limits (Actions minutes, Docker Hub pulls/private repos) | Public repo = unlimited Actions minutes; private repo = 2,000 min/month: path filters keep usage low |

## What changed

| Step | Commit | Result |
|---|---|---|
| plan | (this commit) | this file |
