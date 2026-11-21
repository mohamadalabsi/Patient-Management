---
name: ci-conventions
description: How to build and change a CI/CD pipeline for a repo of backend services - what a pipeline must check and in which order, running only what changed (monorepo path filters), test and end-to-end jobs, container images with immutable tags, vulnerability scanning, secrets and permissions, pinned third-party steps, automated dependency updates, readable failures. Stack-agnostic principles (part A) plus the concrete GitHub Actions + Maven + Docker Hub setup of this repo (part B1) and equivalents for other CI systems, registries and languages (part B2). Use when creating or editing a CI workflow (.github/workflows, .gitlab-ci.yml, Jenkinsfile), Dependabot/Renovate config, a Dockerfile's layering for CI, image tags or registry pushes, when adding a new service that CI must pick up, or when a CI run fails.
---

# CI conventions (established in roadmap Phase 2)

**How to use this skill:** part A holds for any CI system and language. Then use the section that
matches the project: B1 for GitHub Actions + Java/Maven + Docker Hub (this repo), B2 for other stacks.
Background and a plain-language explanation: `docs/implementation-plans/phase-02-ci-docker-hub.md`.
Comment style (`PHASE n (step x.y)`, old lines commented out) comes from `service-conventions` and
applies to pipeline files too: **every job and every step gets a comment saying what it does and why.**

---

## A. Principles (any stack)

### A1. What a pipeline checks, and in which order

Cheap and fast first, slow later, publishing last - each stage only when the previous one passed:

| # | Stage | Runs on | Purpose |
|---|---|---|---|
| 1 | detect changes | every PR + merge | decide which services/stages are needed |
| 2 | build + tests per service (unit, slice, integration with real containers) | every PR + merge | the change works in isolation |
| 3 | end-to-end against the whole stack | every PR + merge, when the stack is affected | the services fit together |
| 4 | build artifact/image + vulnerability scan | every PR + merge | it packages and contains nothing known-bad |
| 5 | publish (push image) | **merges only** | only reviewed, tested code is published |
| 6 | one summary check | always | a single green/red answer for branch protection |

- The pipeline is the **gate of the shared branch**: protect the branch so a red check blocks the merge.
- **Prove the gate works** once: a deliberately failing test must turn the PR red (then revert it).
- Pull requests **never publish** and never receive publishing credentials (PRs from forks can run any code).

### A2. Run only what changed (monorepos)

- One filter per deployable: its folder **plus every shared file it compiles** (e.g. shared API contracts).
  The pipeline file itself belongs to every filter, so changing CI re-tests everything.
- Use the filter result as a **matrix** (one parallel job per changed service) instead of copy-pasted jobs.
- On a PR, filters compare the **whole PR with the target branch**, not the last commit.
- Jobs that exist only sometimes cannot be required by name in branch protection -> add one **summary job**
  that runs `always`, fails if any needed job failed or was cancelled, and is fine with skipped jobs.
- Dependent jobs: a *skipped* prerequisite skips the dependent by default; when that is wrong, say so
  explicitly ("not cancelled and prerequisite did not fail").
- A manual trigger ("run all") is useful; make it select everything.

### A3. Artifacts and tags

- Build once per commit; the image that was scanned is the image that is pushed (reuse the build cache).
- Tag with the **commit** (`sha-<short>`): immutable, deployments use it. Moving tags (`dev`, `latest`)
  are conveniences, never deployment targets.
- Layer the image so rarely-changing parts (runtime, libraries) come first and the app code last:
  a code change re-uploads only a small layer. Use a build-layer cache in CI.
- Keep test reports and coverage as downloadable artifacts, **also when the job failed** (that is when
  you need them); write a short summary (coverage, pushed tags) on the run page.
- Report coverage before enforcing a minimum; a threshold comes once the suite is stable.

### A4. Secrets and permissions

- Least privilege: the pipeline token is read-only by default; a job asks for more only when it needs it.
- Registry access with a **scoped access token** (push only), never an account password; stored in the CI
  system's encrypted secrets, used only in the publishing step, only on merges.
- Test-time secrets (DB passwords, signing keys for the e2e stack) are **generated per run** (random), not
  stored anywhere. Mask them in the log.
- Do not leave credentials behind on the runner (e.g. disable persisting the git token after checkout).

### A5. Supply chain

- Pin every third-party CI step to an **immutable reference** (full commit SHA) with the version as a
  comment. Tags can be moved to malicious code; SHAs cannot.
- Pin runner images and tool versions too (no `latest`): no surprise upgrades.
- Scan images; **fail on critical findings that have a fix**, report the rest. Unfixable findings would
  block every build without anything you can do.
- Automate dependency updates (libraries, base images, CI steps): weekly, small/patch updates grouped per
  service, majors separately; every update PR goes through the same pipeline.

### A6. Failures must be readable and cheap to repeat

- Fail fast is *off* in matrices: one red service must not hide the results of the others.
- On e2e failure, collect state and logs of every container as an artifact; always tear the stack down.
- Use the health checks of the stack to wait for readiness - never fixed sleeps.
- A new push to the same PR cancels the outdated run; runs on protected branches are never cancelled.
- Timeouts on every job.
- **Infrastructure flake vs. real failure:** a network download failing once on the very first run is not
  a code problem - re-run first, read the log, and add a fix only when the log proves the cause.

### A7. Before committing a pipeline change

1. Lint the workflow locally (syntax, expressions, shell scripts inside steps).
2. Validate config files against their schema (dependency-update config).
3. Run the commands of the new step locally the same way CI will (same script, same env file).
4. Push, then **read the run log** - not only the green tick (were steps skipped? cache hits? warnings?).

---

## B1. GitHub Actions + Java/Maven + Docker Hub (this repo)

Files: `.github/workflows/ci.yml`, `.github/dependabot.yml`. Job chain:

```
changes -> test (matrix) -> e2e -> images (matrix) -> ci-result
```

| Job | Key settings |
|---|---|
| `changes` | `dorny/paths-filter`, one filter per service (`patient-service`: its folder + both protos; `billing-service`: + `billing_service.proto`; `analytics-service`: + `patient_event.proto`), each also lists `.github/workflows/ci.yml`; a second filter `stack` for e2e. Outputs `services` (JSON list) and `e2e`. `workflow_dispatch` -> all |
| `test` | `strategy.matrix.service: ${{ fromJSON(needs.changes.outputs.services) }}`, `fail-fast: false`, `working-directory` = service; `setup-java` (temurin 21, `cache: maven`, `cache-dependency-path: <svc>/pom.xml` - also caches the Maven wrapper download); `./mvnw -B verify`; JaCoCo CSV -> `$GITHUB_STEP_SUMMARY`; `upload-artifact` with `if: always()` |
| `e2e` | `if: !cancelled() && needs.changes.outputs.e2e == 'true' && needs.test.result != 'failure'`; `COMPOSE_PROJECT_NAME: pm-e2e`; env file from `openssl rand` into `$RUNNER_TEMP/e2e.env` (+ `::add-mask::`); `docker compose --env-file ... up -d --build --wait --wait-timeout 300`; `mvn -B test -Dgateway.url=http://localhost:4004` in `Integration-tests`; logs on `failure()`; `down -v` on `always()` |
| `images` | `env.PUBLISH: ${{ github.event_name == 'push' }}`; build context `.` for services that copy `proto/`, else the service folder; `setup-buildx`; `metadata-action` (`type=sha,prefix=sha-,format=short`, `dev` on `refs/heads/dev-branch`, `latest` on `refs/heads/master`, `flavor: latest=false`); `build-push-action` `load: true` + `cache-from/to: type=gha,scope=<svc>(,mode=max)`; Trivy report (`HIGH,CRITICAL`, exit 0) + gate (`CRITICAL`, `ignore-unfixed`, exit 1, `skip-setup-trivy`); `login-action` + push only `if: env.PUBLISH == 'true'`; `DOCKER_BUILD_RECORD_UPLOAD: false` |
| `ci-result` | `needs: [all]`, `if: always()`, fails when `join(needs.*.result, ' ')` contains `failure` or `cancelled` |

Workflow-level: `permissions: contents: read` (`changes` adds `pull-requests: read`); `concurrency:
ci-${{ github.event.pull_request.number || github.ref }}` with `cancel-in-progress` only for PRs;
`runs-on: ubuntu-24.04`; `timeout-minutes` on every job; `actions/checkout` with `persist-credentials: false`.

**Pinned actions** (update via Dependabot, never by hand to a tag): checkout v7.0.1, setup-java v6.3.0,
upload-artifact v7.0.2, dorny/paths-filter v4.0.3, setup-buildx v4.4.1, login v4.8.0, metadata v6.2.0,
build-push v7.4.0, trivy-action v0.36.0 (Trivy 0.70.0). Find a tag's SHA without changing anything:
`git ls-remote https://github.com/<owner>/<action> refs/tags/<tag> 'refs/tags/<tag>^{}'`.

**Layered Dockerfile** (build stage, after `mvn package -DskipTests`):

```dockerfile
RUN java -Djarmode=tools -jar target/<svc>-0.0.1-SNAPSHOT.jar extract --layers \
    --destination /workspace/extracted --application-filename app.jar
# runtime stage, least to most frequently changing:
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Dependabot:** `maven` (`directories:` the 5 services + `Integration-tests`), `docker` (5 Dockerfile
dirs, `ignore` semver-major), `docker-compose` (`/`), `github-actions` (`/`, one group); all
`target-branch: dev-branch`, weekly Monday, `groups.minor-and-patch.update-types: [minor, patch]`,
`commit-message.prefix: "chore(deps)"`. Dependabot reads the config from the **default branch**.

**Secrets:** `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` (Read & Write token). The developer sets them;
Claude only checks names with `gh secret list`. Images: `docker.io/<user>/pm-<service>`.

**Adding a service:** add its filter in `changes` (+ the shared contracts it compiles), its case in the
`images` build-context step if it builds from the repo root, its directories in `dependabot.yml`, the
layered Dockerfile, executable `mvnw` (`git update-index --chmod=+x <svc>/mvnw`).

**Check locally before committing:**

```bash
docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12          # workflow lint (+ shellcheck)
check-jsonschema --builtin-schema vendor.dependabot .github/dependabot.yml  # in a throwaway venv
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.70.0 \
  image --severity HIGH,CRITICAL <image>                                    # same scan as CI
```

Run the e2e commands with an own project name and a scratch env file; if the developer's stopped
containers use the fixed `container_name`s, add a temporary override file with other names - never
remove the developer's containers or volumes. Read runs with `gh run list/view/watch`,
`gh run view --job=<id> --log`, `--log-failed`.

**Gotchas learned:**
- `runner.*` is not allowed in job-level `env:` -> use `$RUNNER_TEMP` inside `run:`.
- `setup-java` v6 already caches `~/.m2/wrapper`; do not add a second cache for it.
- A secret used in an image name (`DOCKERHUB_USERNAME`) is masked as `***` in every log line.
- On a PR the `sha-` tag is GitHub's merge-preview commit, not the branch head.
- zsh: `$var:l...` is a modifier (`$s:latest` breaks) -> write `${s}:latest`; a command stored in a
  variable is not word-split -> use a function.

---

## B2. The same principles in other stacks

| Concern | GitHub Actions (this repo) | GitLab CI | Jenkins | Others |
|---|---|---|---|---|
| Pipeline file | `.github/workflows/*.yml` | `.gitlab-ci.yml` | `Jenkinsfile` | CircleCI `.circleci/config.yml`, Azure `azure-pipelines.yml` |
| Order / dependencies | `needs:` | `stages:` + `needs:` | `stages { stage {...} }` | CircleCI `requires:` |
| Only changed paths | `dorny/paths-filter` | `rules: changes:` | `when { changeset "svc/**" }` | Nx/Turborepo `affected`, Bazel |
| Matrix | `strategy.matrix` | `parallel: matrix:` | `matrix { axes {...} }` | CircleCI `matrix` |
| Summary check | `if: always()` job over `needs.*.result` | pipeline status | overall build result | - |
| Secrets | repository/environment secrets | CI/CD variables (masked, protected) | Credentials plugin | Vault, cloud secret managers |
| Least privilege | `permissions:` | job token scope, protected variables | folder-scoped credentials | OIDC to the cloud instead of long-lived keys |
| Pin steps | action `@<sha>` | `include:` with `ref: <sha>`, image digests | pinned plugin versions | image `@sha256:` digests |
| Layer cache | `cache-to/from: type=gha` | `--cache-from` registry cache | registry cache | `type=registry` / `type=s3` |
| Registry | Docker Hub | GitLab Container Registry | any | GHCR, AWS ECR, Google Artifact Registry, Azure ACR |
| Scanning | Trivy action | Trivy / built-in container scanning | Trivy CLI | Grype, Docker Scout, Snyk |
| Dependency updates | Dependabot | Renovate, GitLab dependency scanning | Renovate | Renovate (works everywhere) |
| Workflow lint | actionlint | `gitlab-ci-lint` / CI Lint page | Jenkins linter (`declarative-linter`) | `circleci config validate` |

| Concern | Java / Maven (this repo) | Node.js / TypeScript | Python | Go |
|---|---|---|---|---|
| Toolchain + cache | `setup-java` `cache: maven` | `setup-node` `cache: npm` | `setup-python` `cache: pip` / `setup-uv` | `setup-go` (caches modules) |
| Build + tests | `./mvnw -B verify` | `npm ci && npm test` | `pytest` | `go test ./...` |
| Coverage | JaCoCo | c8 / Vitest/Jest `--coverage` | coverage.py / pytest-cov | `go test -coverprofile` |
| Layered image | Spring Boot `extract --layers` | copy `package*.json`, `npm ci`, then sources; multi-stage | install deps layer, then app; multi-stage | multi-stage, static binary on `distroless`/`scratch` |
