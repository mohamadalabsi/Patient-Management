---
name: ci-conventions
description: How to build and change a CI/CD pipeline for a repository of backend services - what a pipeline checks and in which order, running only what changed (monorepo path filters), test and end-to-end jobs, container images with immutable tags, layered images and build caches, vulnerability scanning, secrets and least privilege, pinned third-party steps, automated dependency updates, readable failures. Principles for any CI system (part A), the concrete GitHub Actions + Java/Maven + Docker registry implementation (part B), equivalents for other CI systems and languages (part C). Project-agnostic. Use when creating or editing a CI workflow (.github/workflows, .gitlab-ci.yml, Jenkinsfile), Dependabot/Renovate config, image tags or registry pushes, when adding a service that CI must pick up, or when a CI run fails.
---

# CI conventions

**How to use this skill:** part A holds for every CI system and language. Part B is the default
implementation for GitHub Actions + Java/Maven services + a Docker registry. Part C maps it to other
systems. Project-specific facts (branch names, service list, secret names) belong in the project's docs.
Comment style from `service-conventions` applies to pipeline files too: **every job and every step gets
a comment saying what it does and why.**

---

## A. Principles (any CI system)

### A1. What a pipeline checks, and in which order

Cheap and fast first, slow later, publishing last - each stage only when the previous one passed:

| # | Stage | Runs on | Purpose |
|---|---|---|---|
| 1 | detect changes | every PR + merge | decide which services/stages are needed |
| 2 | build + tests per service (unit, slice, integration with real containers) | every PR + merge | the change works in isolation |
| 3 | end-to-end against the whole stack | every PR + merge, when the stack is affected | the services fit together |
| 4 | build image + vulnerability scan | every PR + merge | it packages and contains nothing known-bad |
| 5 | publish (push image) | **merges only** | only reviewed, tested code is published |
| 6 | (later) deploy | merges to deploy branches / tags | CD - separate, gated stage |
| 7 | one summary check | always | a single green/red answer for branch protection |

- The pipeline is the **gate of the shared branch**: protect the branch so a red check blocks the merge.
- **Prove the gate works** once: a deliberately failing test must turn the PR red (then revert it).
- Pull requests **never publish** and never receive publishing credentials (PRs from forks run any code).

### A2. Run only what changed (monorepos)

- One filter per deployable: its folder **plus every shared file it compiles** (shared contracts). The
  pipeline file itself belongs to every filter, so changing CI re-tests everything.
- Use the filter result as a **matrix** (one parallel job per changed service), not copy-pasted jobs.
- On a PR, filters compare the **whole PR with the target branch**, not the last commit.
- Jobs that exist only sometimes can't be required by name -> one **summary job** that always runs,
  fails if any needed job failed or was cancelled, and accepts skipped jobs.
- A skipped prerequisite skips dependents by default; when wrong, say "not cancelled and prerequisite
  did not fail" explicitly.
- A manual trigger ("run all") selects everything.

### A3. Artifacts and tags

- Build once per commit; the image that was scanned is the image that is pushed.
- Tag with the **commit** (`sha-<short>`): immutable, deployments use it. Moving tags (`dev`, `latest`)
  are conveniences, never deployment targets.
- Layer images so rarely-changing parts (runtime, libraries) come first and app code last; use a build
  layer cache in CI.
- Keep test reports and coverage as artifacts, **also when the job failed**; write a short summary
  (coverage, pushed tags) on the run page.
- Report coverage before enforcing a minimum; a threshold comes once the suite is stable.

### A4. Secrets and permissions

- Least privilege: the pipeline token is read-only by default; a job asks for more only when needed.
- Registry access with a **scoped access token**, never an account password; stored in the CI system's
  encrypted secrets, used only in the publishing step, only on merges. For clouds: short-lived OIDC
  federation instead of long-lived keys.
- Test-time secrets (DB passwords, e2e keys) are **generated per run**, never stored; mask them.
- Identity providers in CI: use a **local fake issuer**, not the real tenant (no internet dependency,
  no provider secrets in CI).
- Don't leave credentials on the runner (e.g. don't persist the git token after checkout).

### A5. Supply chain

- Pin every third-party CI step to an **immutable reference** (full commit SHA) with the version as a
  comment. Tags can be moved; SHAs can't.
- Pin runner images and tool versions (no `latest`).
- Scan images; **fail on critical findings that have a fix**, report the rest.
- Automate dependency updates (libraries, base images, CI steps): weekly, minor/patch grouped per
  service, majors separately; every update PR goes through the same pipeline.

### A6. Failures must be readable and cheap to repeat

- `fail-fast` off in matrices: one red service must not hide the others.
- On e2e failure, collect state and logs of every container as an artifact; always tear the stack down.
- Wait for readiness with health checks, never fixed sleeps.
- A new push to the same PR cancels the outdated run; protected-branch runs are never cancelled.
- Timeouts on every job.
- **Infrastructure flake vs real failure:** a download failing once (registry rate limit, network) is
  re-run first; add a fix only when the log proves the cause.

### A7. Where the build happens

- Either the **Dockerfile builds the artifact** (multi-stage: compile inside the image; the developer
  needs only Docker; CI just calls `docker build`), or **CI builds the artifact** and the Dockerfile only
  copies it (faster CI, but local runs need the toolchain). Pick one per repo; don't build twice by accident.
- Tests always run as their own CI step (container-based tests can't run inside `docker build`).

### A8. Before committing a pipeline change

1. Lint the workflow locally (syntax, expressions, shell inside steps).
2. Validate config files against their schema (dependency-update config).
3. Run the new step's commands locally the same way CI will (same script, same env file).
4. Push, then **read the run log** - not only the green tick (skipped steps? cache hits? warnings?).

---

## B. GitHub Actions + Java/Maven + Docker registry

Files: `.github/workflows/ci.yml`, `.github/dependabot.yml`. Job chain:

```
changes -> test (matrix) -> e2e -> images (matrix) -> ci-result
```

| Job | Key settings |
|---|---|
| `changes` | `dorny/paths-filter`: one filter per service (its folder + shared contracts + the workflow file); a second filter `stack` for e2e. Outputs `services` (JSON list) and `e2e`. `workflow_dispatch` -> all |
| `test` | `strategy.matrix.service: ${{ fromJSON(needs.changes.outputs.services) }}`, `fail-fast: false`, `working-directory` = service; `actions/setup-java` (temurin 21, `cache: maven`, `cache-dependency-path: <svc>/pom.xml` - also caches the Maven wrapper); `./mvnw -B verify`; JaCoCo CSV -> `$GITHUB_STEP_SUMMARY`; `actions/upload-artifact` with `if: always()` |
| `e2e` | `if: ${{ !cancelled() && needs.changes.outputs.e2e == 'true' && needs.test.result != 'failure' }}`; `COMPOSE_PROJECT_NAME` of its own; `COMPOSE_FILE` lists the base file + an e2e override (fake issuer etc.); env file generated with `openssl rand` into `$RUNNER_TEMP` (+ `::add-mask::`); `docker compose up -d --build --wait --wait-timeout 300`; e2e tests; logs on `failure()`; `down -v` on `always()` |
| `images` | `PUBLISH: ${{ github.event_name == 'push' }}`; `docker/setup-buildx-action`; `docker/metadata-action` (`type=sha,prefix=sha-,format=short`, branch tags, `flavor: latest=false`); `docker/build-push-action` with `load: true` + `cache-from/to: type=gha,scope=<svc>,mode=max`; Trivy report (`HIGH,CRITICAL`, exit 0) + gate (`CRITICAL`, `ignore-unfixed: true`, exit 1); `docker/login-action` + push only when `PUBLISH`; `DOCKER_BUILD_RECORD_UPLOAD: false` |
| `ci-result` | `needs: [all jobs]`, `if: always()`, fails when `join(needs.*.result, ' ')` contains `failure` or `cancelled` |

Workflow level: `permissions: contents: read` (add `pull-requests: read` where paths-filter needs it);
`concurrency: ci-${{ github.event.pull_request.number || github.ref }}` with `cancel-in-progress` only
for PRs; pinned `runs-on` (e.g. `ubuntu-24.04`); `timeout-minutes` on every job;
`actions/checkout` with `persist-credentials: false`.

**Pinning:** `uses: owner/action@<40-char sha> # vX.Y.Z`. Find a tag's SHA:
`git ls-remote https://github.com/<owner>/<action> refs/tags/<tag> 'refs/tags/<tag>^{}'`.
Dependabot updates both the SHA and the comment.

**Layered Spring Boot image:** `java -Djarmode=tools -jar app.jar extract --layers --destination extracted
--application-filename app.jar`, then four `COPY` lines (dependencies, spring-boot-loader,
snapshot-dependencies, application) - see `service-conventions` B5.

**Dependabot:** ecosystems `maven` (`directories:` every service), `docker` (Dockerfile dirs, ignore
semver-major base image updates), `docker-compose` (`/`), `github-actions` (`/`, one group);
`target-branch: <integration branch>`, weekly, `groups.minor-and-patch.update-types: [minor, patch]`,
`commit-message.prefix: "chore(deps)"`. Dependabot reads the config from the **default branch**.

**Secrets:** registry user + access token (Read & Write) as repository secrets; set by a maintainer.

**Adding a service:** its filter in `changes` (+ shared contracts), its build context in `images`, its
directories in `dependabot.yml`, the layered Dockerfile, an executable `mvnw`
(`git update-index --chmod=+x <svc>/mvnw`).

**Check locally:**

```bash
docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:<version>         # lint (+ shellcheck)
check-jsonschema --builtin-schema vendor.dependabot .github/dependabot.yml   # dependabot schema
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:<version> \
  image --severity HIGH,CRITICAL <image>                                     # same scan as CI
gh run list / gh run view <id> --log-failed                                  # read runs
```

Run e2e commands locally with an own project name, a scratch env file and (if needed) an override file
with other container names/ports - never touch the developer's running containers or volumes.

**Gotchas:**
- `runner.*` isn't allowed in job-level `env:` -> use `$RUNNER_TEMP` inside `run:`.
- `setup-java` already caches `~/.m2/wrapper`; don't add a second cache for it.
- A secret used in an image name is masked as `***` in every log line.
- On a PR the `sha-` tag is the merge-preview commit, not the branch head.
- GitHub caches are scoped per branch: the first run after a merge starts cold (more downloads ->
  possible registry rate limits).

---

## C. The same principles elsewhere

| Concern | GitHub Actions | GitLab CI | Jenkins | Others |
|---|---|---|---|---|
| Pipeline file | `.github/workflows/*.yml` | `.gitlab-ci.yml` | `Jenkinsfile` | CircleCI, Azure Pipelines |
| Order | `needs:` | `stages:` + `needs:` | `stages { stage {...} }` | CircleCI `requires:` |
| Changed paths | `dorny/paths-filter` | `rules: changes:` | `when { changeset }` | Nx/Turborepo `affected`, Bazel |
| Matrix | `strategy.matrix` | `parallel: matrix:` | `matrix { axes }` | CircleCI `matrix` |
| Secrets | repository/environment secrets | masked, protected variables | Credentials plugin | Vault, cloud secret managers |
| Pin steps | `@<sha>` | `include:` `ref: <sha>`, image digests | pinned plugin versions | `@sha256:` digests |
| Layer cache | `type=gha` | registry cache | registry cache | `type=registry` / `type=s3` |
| Registry | Docker Hub, GHCR | GitLab registry | any | ECR, Artifact Registry, ACR |
| Scanning | Trivy | Trivy / container scanning | Trivy CLI | Grype, Docker Scout, Snyk |
| Dependency updates | Dependabot | Renovate | Renovate | Renovate |

| Concern | Java / Maven | Node.js | Python | Go |
|---|---|---|---|---|
| Toolchain + cache | `setup-java` `cache: maven` | `setup-node` `cache: npm` | `setup-python` / `setup-uv` | `setup-go` |
| Build + tests | `./mvnw -B verify` | `npm ci && npm test` | `pytest` | `go test ./...` |
| Coverage | JaCoCo | Vitest/Jest `--coverage` | pytest-cov | `-coverprofile` |
| Layered image | Boot `extract --layers` | deps layer, then sources | deps layer, then app | static binary on distroless |
