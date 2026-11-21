# Phase 2 — CI + Docker Hub

| | |
|---|---|
| Branch | `feature/phase-02-ci-docker-hub` (from `dev-branch` after PR #3) |
| Depends on | Phase 1 (the tests CI runs) |
| Status | ✅ done (2026-11-21) - merged via the Phase 2 PR (#4) |
| Roadmap row | GitHub Actions per service (path filters) · build/test · images on Docker Hub tagged by git SHA · Dependabot · Trivy · Docker improvements |

## How to read this file

You should understand the whole phase from this file alone, without opening the code.
The **Technology map** says which tools are used and why. **Steps** says, for every step, *what* is done,
*why*, *how*, *when* you would use the same technique elsewhere, and *how it is verified*.
In the workflow YAML every job and every step gets a `# PHASE 2 (step 2.x)` comment.
**"What you need to do"** lists the things only you can do (accounts, tokens, GitHub settings), and
**"Run order"** says exactly who does what, in which order, from approval to merge.

## CI/CD in plain words (read this first)

### What is CI/CD?

- **CI = Continuous Integration.** Every time someone pushes code, a machine that is not your laptop
  takes that code, builds it and runs all the checks (tests, scans). If anything fails, the pull request
  shows a red ✗ and the change should not be merged. "Integration" means: everybody's changes are
  merged into the shared branch often, and each merge is checked automatically, so a broken change is
  caught within minutes - not weeks later on someone else's machine.
- **CD = Continuous Delivery / Deployment.** After CI is green, the result is *packaged and shipped*:
  - *Delivery* = the finished, tested package (for us: a Docker image) is put in a place where it can be
    deployed at any time (for us: Docker Hub).
  - *Deployment* = it is also started on servers automatically.
  This phase does **CI + delivery**. Deployment comes in Phase 7 (Kubernetes) and Phase 8 (AWS), which
  will pull the images we publish now.

A useful picture: CI is the **quality gate** at the door of `dev-branch`; CD is the **conveyor belt**
that takes whatever passed the gate to the warehouse (Docker Hub).

### How we use it in this project

One file, `.github/workflows/ci.yml`, runs on GitHub's machines whenever:

| Event | What happens |
|---|---|
| you push to a branch that has a **pull request** into `dev-branch` / `master` | tests + end-to-end tests + images are **built and scanned**, nothing is published |
| a PR is **merged** (= a push to `dev-branch` / `master`) | the same checks again, and then the images are **pushed to Docker Hub** |
| you click "Run workflow" in the Actions tab | everything, for all 5 services |

It only works on **what changed**: a PR that touches only `billing-service/` tests and builds only
billing. A second file, `.github/dependabot.yml`, makes GitHub open update PRs once a week; those PRs
go through the same CI.

### How the jobs are connected

```
                  push / pull request
                         │
                ┌────────▼─────────┐
                │ changes          │  which services changed? -> ["billing-service", ...]
                └────────┬─────────┘                            does the whole stack need a run?
                         │
        ┌────────────────┼────────────────┐   one job per changed service, in parallel
  ┌─────▼──────┐   ┌─────▼──────┐   ┌─────▼──────┐
  │ test       │   │ test       │   │ test       │  mvn verify: unit + Testcontainers tests,
  │ patient    │   │ billing    │   │ ...        │  JaCoCo coverage, reports as artifacts
  └─────┬──────┘   └─────┬──────┘   └─────┬──────┘
        └────────────────┼────────────────┘   only if no test failed
                ┌────────▼─────────┐
                │ e2e              │  docker compose up --wait (whole stack, throwaway .env)
                └────────┬─────────┘  + Integration-tests through the gateway
                         │            only if e2e passed
        ┌────────────────┼────────────────┐
  ┌─────▼──────┐   ┌─────▼──────┐   ┌─────▼──────┐
  │ images     │   │ images     │   │ images     │  build -> Trivy scan -> (after merge) push
  │ patient    │   │ billing    │   │ ...        │  docker.io/<you>/pm-<service>:sha-<commit>
  └─────┬──────┘   └─────┬──────┘   └─────┬──────┘
        └────────────────┼────────────────┘
                ┌────────▼─────────┐
                │ ci-result        │  ONE green/red answer for the PR (branch protection uses it)
                └──────────────────┘
```

Each box is a **job** = a fresh Linux machine. An arrow is `needs:` = "start only after that job, and
only if it succeeded". The order is deliberate: the cheap, fast checks first, the slow ones only when
the fast ones passed, and publishing last - so **only tested code is ever published**.

### What we did (the result)

1. **Tests on every PR** (step 2.1): the 5 services' tests run on GitHub, in parallel, only for the changed
   services; coverage is shown on the run page. Proven by a deliberately broken test that turned the PR red.
2. **End-to-end tests on every PR** (step 2.2): the full stack starts in CI exactly like on your laptop and
   the 9 REST Assured journeys run against it.
3. **Images** (step 2.3): every changed service's image is built and scanned for known vulnerabilities;
   after a merge it is pushed to Docker Hub with an exact commit tag (`sha-…`) plus `dev` / `latest`.
4. **Automatic updates** (step 2.4): Dependabot proposes library, base-image and action updates weekly.

A full run takes about 5 minutes (tests ~1.5 min, e2e ~2 min, images ~1.5 min with a warm cache).

### Technologies, in one line each

| Technology | In one line |
|---|---|
| GitHub Actions | GitHub's built-in CI: YAML in `.github/workflows/`, runs on GitHub's Linux machines |
| `dorny/paths-filter` | answers "which folders changed?" so only those services run |
| Job matrix | "run this job once per service in the list", in parallel |
| `actions/setup-java` | installs Java 21 and caches Maven downloads between runs |
| Testcontainers | the Phase 1 tests start real Postgres/Kafka - works on GitHub's machines as-is |
| JaCoCo | measures which lines the tests executed (coverage report) |
| Docker Compose | the same `docker-compose.yml` as locally starts the whole stack for the e2e job |
| Docker Buildx + build-push-action | builds images, with a layer cache stored in GitHub |
| metadata-action | turns git info into image tags (`sha-abc1234`, `dev`, `latest`) |
| login-action | logs in to Docker Hub with the token from GitHub secrets |
| Trivy | scans an image for known vulnerabilities (CVEs) before it is published |
| Spring Boot layered jars | splits the app into "libraries" and "our code" layers so pushes are small |
| Dependabot | GitHub bot that opens a PR when a dependency has a new version |
| actionlint, check-jsonschema | local linters that catch YAML mistakes before pushing |

### What we had to change in the project to make it work

| Change | Why it was needed |
|---|---|
| `mvnw` files stored as **executable** in git (`chmod +x` via `git update-index`) | on Linux `./mvnw` failed with "permission denied"; Windows does not track the flag |
| **JaCoCo** plugin added to the 5 POMs | to get a coverage number in CI (it attaches to the `@{argLine}` hook prepared in Phase 1) |
| Nothing for **Testcontainers** | GitHub's machines have Docker; the Phase 1 tests ran unchanged |
| Nothing in `docker-compose.yml` | `--wait` uses the Phase 0 healthchecks; CI only provides a generated env file with random passwords |
| **Dockerfiles**: jar split into layers | so a code change uploads ~100 kB instead of the whole ~110 MB jar |
| Two GitHub **secrets** (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`) set by you | so CI can push to Docker Hub without a password in the repo |

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

## Run order - who does what

The exact order of the phase. **You** = the developer, **Claude** = me. I stop at every "You" line
and wait until you tell me it is done.

| # | Who | What |
|---|---|---|
| 1 | You | Read this plan and approve it (or ask for changes). |
| 2 | You | *Optional, makes 4-17 easier:* install the GitHub CLI (`sudo apt install gh`, then `! gh auth login`) so I can read CI runs and logs myself. Without it I read the run status through GitHub's public API, and you paste the log of a failing step. |
| 3 | Claude | **Step 2.1**: write `.github/workflows/ci.yml` (jobs `changes` + `test`), JaCoCo in the 5 POMs, make `mvnw` executable. Check locally that `mvn verify` produces the coverage report. Commit. |
| 4 | You | Push the branch: `git push -u origin feature/phase-02-ci-docker-hub`. |
| 5 | You | Open a **draft pull request** `feature/phase-02-ci-docker-hub` -> `dev-branch` on GitHub. From now on, every push to the branch runs CI on that PR. |
| 6 | Claude | Read the run together with you: which jobs ran, were they green, how long they took. If something fails: fix, commit, back to 4 (push only). |
| 7 | Claude | Prove the check works: a temporary commit that breaks one test. |
| 8 | You | Push. The PR check must turn **red**. |
| 9 | Claude | Revert the temporary commit (`git revert`, so history shows the experiment). |
| 10 | You | Push. Green again. |
| 11 | Claude | **Step 2.2**: add the `e2e` job (compose stack with a generated `.env`, `Integration-tests`, logs on failure). Commit. |
| 12 | You | Push. |
| 13 | Claude | Read the run (e2e green, runtime noted). Fix and repeat 12 if needed. |
| 14 | You | **Docker Hub**: create the access token (Read & Write). Optional: create the 5 repositories if they should be private. |
| 15 | You | **GitHub**: add the secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`. Tell me "secrets done". |
| 16 | Claude | **Step 2.3**: add the `images` job (build, Trivy, push only after a merge) and layered jars in the 5 Dockerfiles. Check locally: `docker compose up --build` still healthy, E2E still green. Commit. |
| 17 | You | Push. On the PR the images are **built and scanned but not pushed**; I read the Trivy result with you. |
| 18 | Claude | **Step 2.4**: `.github/dependabot.yml`. Commit. |
| 19 | Claude | **Step 2.5**: docs (README CI/CD, CLAUDE.md, roadmap, this file's "What changed"). Commit. |
| 20 | Claude | **Step 2.6**: skill `ci-conventions`. Commit. Report the phase as finished. |
| 21 | You | Push. Mark the PR "Ready for review", check that all checks are green, merge into `dev-branch`. |
| 22 | You | The merge is a push to `dev-branch`, so the `images` job now runs for real. Check Docker Hub: the changed services have tags `sha-<commit>` and `dev`. Tell me. |
| 23 | Claude | Verify one image: `docker pull` + `docker run`, and look at its layers. (These results go into the first commit of Phase 3, because this branch is already merged.) |
| 24 | You | Protect `dev-branch` (Settings -> Branches: require the CI checks) and turn on Dependabot alerts (Settings -> Code security). |
| 25 | You | Create the Phase 3 branch from `dev-branch`. |

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
  - Dockerfile build stage: `java -Djarmode=tools -jar app.jar extract --layers --application-filename app.jar`;
    runtime stage: one `COPY` per layer (dependencies, spring-boot-loader, snapshot-dependencies, application).
    The old single `COPY` stays commented out. *Changed during the step:* the plan said `--launcher`
    (start via `JarLauncher`); without it the `application` layer holds a thin `app.jar` that finds its
    libraries in `lib/`, so `ENTRYPOINT java -jar app.jar` stays unchanged - simpler, and Spring's
    recommended form.
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

- [x] A PR runs only the jobs of the changed services; all green (in this PR all 5 run, because `ci.yml` and all POMs changed - the filter compares the whole PR with `dev-branch`)
- [x] A broken test turns the PR check red
- [x] E2E tests run in CI against the compose stack, with logs attached on failure (failure path checked locally)
- [ ] After a merge into `dev-branch`, Docker Hub has `sha-<commit>` and `dev` tags for the changed services (checked after the merge, run order 22)
- [x] Trivy runs before every push; no image with a fixable critical CVE is published
- [x] No secret appears in the repo or in the logs
- [x] Dependabot config is valid (schema-checked; GitHub shows it once it is on `master`)
- [x] CI runtime noted (budget for later phases): ~5 min per full run

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
| plan | `0eb916c`, `62c6f30` | this file; run-order table added, rule added to CLAUDE.md |
| 2.1 | `7164c58` | `ci.yml` with `changes` -> `test` matrix -> `ci-result`; JaCoCo in 5 POMs; `mvnw` executable. First run: Testcontainers worked on the runner unchanged; api-gateway failed once downloading Maven (network glitch on the very first run, green on re-run). Runtimes: patient 1m40s, auth 1m, others ~45s |
| 2.1 (fix, reverted) | `cb2f4b8` -> `7bb0fb7` | added an `actions/cache` for `~/.m2/wrapper`; the next run's log showed `setup-java` v6 **already caches the Maven wrapper**, so the step was a duplicate (and printed "Failed to save" in 4 jobs) - reverted. Lesson: read the log of a run before adding a fix |
| 2.1 (proof) | `b980130` -> `17e4b63` | temporary wrong assertion in `PatientMapperTest`: `Test patient-service` and `CI result` red (`expected: "Anna Broken" but was: "Anna Test"`), reports still uploaded; reverted, green again |
| 2.2 | `df8a7fb` | `e2e` job: throwaway env file (`openssl rand`), `docker compose up --build --wait`, 9/9 E2E green in CI (2m9s). Locally: billing stopped -> 2 failures (503), logs collected. actionlint caught `runner.temp` in job-level `env` (not allowed) -> `$RUNNER_TEMP` in the steps |
| 2.3 | `47be382` | `images` job (Buildx, gha cache, metadata tags, Trivy report + gate, push only on push events); layered jars in 5 Dockerfiles (without `--launcher`, see step 2.3). patient: app layer 94 kB vs 106 MB dependencies. Trivy: 0 CRITICAL; 10 HIGH with fixes - `grpc-netty-shaded` 1.69 (pinned by hand), `guava`, `lz4-java`, `snappy-java` (via kafka-clients) - left for Dependabot |
| 2.4 | `781599e` | `dependabot.yml` (maven ×6 dirs, docker ×5, docker-compose, github-actions; minor+patch grouped per directory; docker majors ignored); `DOCKER_BUILD_RECORD_UPLOAD: false`. With the warm layer cache the image jobs went from 2m17s-3m6s to 49s-1m45s |
| 2.5 | (this commit) | README "CI/CD" section + badge, CLAUDE.md CI paragraph, roadmap done, this file's plain-language section |
| 2.6 | next commit | skill `ci-conventions` |

Still open after the merge (run order 22-24): check Docker Hub tags, pull + run one image, branch
protection on `dev-branch`, Dependabot alerts. The results go into the first commit of Phase 3.
