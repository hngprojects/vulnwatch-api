# Contributing to VulnWatch

Welcome to the team. This guide covers everything you need to know to contribute safely and predictably. Please read it fully before opening your first PR.

---

## 📐 Branch Strategy

```
main          ← production (never commit directly)
└── staging   ← staging / pre-prod validation (never commit directly)
    └── dev   ← integration branch (never commit directly)
        └── feat/your-feature   ← all development work
        └── fix/some-bug
        └── chore/update-deps
        └── hotfix/critical-fix  ← branches from main, merges back to main + dev
```

### Branch Naming Convention

| Prefix      | Use for                                 |
| ----------- | --------------------------------------- |
| `feat/`     | New features                            |
| `fix/`      | Bug fixes                               |
| `chore/`    | Maintenance (deps, config, etc.)        |
| `docs/`     | Documentation only                      |
| `hotfix/`   | Urgent production fixes                 |
| `refactor/` | Code refactors without behaviour change |

**Examples:** `feat/scan-scheduler`, `fix/auth-token-expiry`, `hotfix/db-connection-leak`

---

## 🔀 Merge Flow

```
feature branch → dev → staging → main
```

1. **Feature → dev** via Pull Request

   - Minimum **1 approval**
   - All CI checks must pass
   - Branch must be up-to-date with `dev` before merge

2. **dev → staging** via Pull Request

   - After sufficient features/fixes are integrated and tested on `dev`
   - Minimum **1 approval** (2 for auth, security, or critical paths)
   - All CI checks must pass
   - Triggered by the team lead / release owner

3. **staging → main** via Pull Request

   - Only after successful deployment and validation on staging
   - Requires **2 approvals**
   - Triggered by DevOps / release owner

4. **Never** merge feature branches directly to `staging` or `main`

---

## 🚀 Starting New Work

```bash
# Always start from dev
git fetch origin
git checkout dev
git pull origin dev

git checkout -b feat/your-feature-name
```

Keep your branch up to date frequently:

```bash
git fetch origin
git rebase origin/dev
```

---

## ✅ Before Opening a PR

- [ ] Branch is up-to-date with `dev`
- [ ] `dotnet test` / `mvn test` passes locally
- [ ] `dotnet format` / `mvn spotless:check` passes (no formatting issues)
- [ ] No secrets or hardcoded credentials in code
- [ ] PR template is filled out completely
- [ ] CI checks pass on your branch (push it, watch the checks)

---

## 🔥 Hotfix Process

When a critical bug is live in production:

```bash
# 1. Branch from main
git fetch origin
git checkout main
git pull origin main
git checkout -b hotfix/describe-the-bug

# 2. Fix the bug, commit, push
git push origin hotfix/describe-the-bug

# 3. Open a PR → main (gets 2 approvals, then merged)
# 4. An automated PR to backport to dev will be created automatically
# 5. Review and merge the backport PR to keep dev in sync
```

> ⚠️ Never fix directly on `main`. Always go through a PR even for hotfixes.

---

## 🏗️ Project Services

| Service     | Language              | Directory      | Owner       |
| ----------- | --------------------- | -------------- | ----------- |
| REST API    | C# / ASP.NET Core 8   | `api-dotnet/`  | API Team    |
| Scan Worker | Java 21 / Spring Boot | `worker-java/` | Worker Team |

### Running Locally

Make sure you have Postgres and Redis running locally (via your OS package manager or a local install), then:

```bash
# API (port 5000)
# Copy and fill in your local env values first
cp api-dotnet/src/Web/appsettings.json api-dotnet/src/Web/appsettings.Development.json
cd api-dotnet && dotnet run --project src/Web

# Worker (port 8080)
# Copy and fill in your local env values first
cp worker-java/src/main/resources/application-example.properties worker-java/src/main/resources/application.properties
cd worker-java && ./mvnw spring-boot:run
```

---

## 🔐 Secrets & Environment Variables

- **Never hardcode secrets.** Use environment variables.
- Use `.env` files locally (never commit them — they are gitignored).
- Production secrets are managed via **AWS Secrets Manager** (or Vault — see DevOps).
- If you need a new secret in CI, ask DevOps to add it to GitHub Environments.

| Environment | Secret Source                                                         |
| ----------- | --------------------------------------------------------------------- |
| Local dev   | `.env` file (gitignored)                                              |
| Staging     | GitHub Actions `staging` environment secrets                          |
| Production  | GitHub Actions `production` environment secrets + AWS Secrets Manager |

---

## 🧪 Testing Standards

### C# (api-dotnet)

```bash
cd api-dotnet
dotnet test                          # run all tests
dotnet format --verify-no-changes    # check formatting
```

### Java (worker-java)

```bash
cd worker-java
./mvnw test                          # run all tests
./mvnw spotless:check                # check formatting
./mvnw spotless:apply                # auto-fix formatting
```

Write tests for:

- All service/business logic
- All HTTP endpoints (integration tests where possible)
- Edge cases and error paths

---

## 🔍 Code Review Guidelines

**As an author:**

- Keep PRs focused — one feature/fix per PR
- Add context in the PR description, not just the ticket link
- Respond to review comments promptly

**As a reviewer:**

- Review within 24 hours of being assigned
- Check for: correctness, security, performance, breaking changes
- Don't approve code you don't understand

**No self-merging under any circumstance.**

---

## 📦 Releases & Versioning

- Releases follow [Semantic Versioning](https://semver.org/): `vMAJOR.MINOR.PATCH`
- Tags are created on `prod` before each production deployment
- Every production deployment creates a GitHub Release automatically (via CI)

```bash
# Create a release tag (done by release owner)
git tag v1.2.0
git push origin v1.2.0
```

---

## 🔄 Rollback Procedure

If a production deployment fails:

1. Notify the team immediately in the DevOps channel
2. SSH into the prod server — the CD pipeline automatically snapshots the previous build before every deployment under `/opt/vulnwatch/rollback/`
3. Restore the previous build:

```bash
# On prod server — restore API
sudo systemctl stop vulnwatch-api
cp -r /opt/vulnwatch/rollback/api-<TIMESTAMP>/* /opt/vulnwatch/api/
sudo systemctl start vulnwatch-api

# Restore Worker JAR
sudo systemctl stop vulnwatch-worker
cp /opt/vulnwatch/rollback/vulnwatch-worker-<TIMESTAMP>.jar /opt/vulnwatch/worker/vulnwatch-worker.jar
sudo systemctl start vulnwatch-worker
```

4. Verify services are healthy:

```bash
sudo systemctl status vulnwatch-api vulnwatch-worker
journalctl -u vulnwatch-api -n 50
journalctl -u vulnwatch-worker -n 50
```

5. Open a post-mortem issue in GitHub

---

## 📢 Communication Rules

- **API contract changes** → announce in `#backend` at least 24h before merging
- **DB migrations** → announce in `#devops` and coordinate with the worker team
- **Dependency major version bumps** → must be discussed before merging
- **Breaking changes** → document in PR and update relevant README/docs

---

## ❓ Questions

Reach out to your team lead or DevOps before doing something you're unsure about.  
It's always better to ask than to break `prod`.
