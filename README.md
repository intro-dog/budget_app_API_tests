# Budget API — Test Automation Suite

API regression suite for a household budgeting service, written against the
live HTTP contract rather than the implementation. The application under test
is a FastAPI backend with PostgreSQL and Redis; its source lives in a separate
private repository, so every assertion here is made from the outside.

**Java 17 · REST Assured · JUnit 5 · AssertJ · Allure · Ollama · GitHub Actions**

## Live API

The suite runs against a deployed instance. The contract is browsable without
credentials:

| | |
|---|---|
| Swagger UI |https://split-ly.com/api/docs |
| Scalar | https://split-ly.com/api/scalar |
| ReDoc | https://split-ly.com/api/redoc |
| OpenAPI schema | https://split-ly.com/api/openapi.json |

## What is covered

The suite targets the parts of the contract where a defect is expensive and a
casual read of the docs is not enough to tell whether the behaviour is correct.

### Authentication

| Behaviour | Why it is worth a test |
|---|---|
| OAuth2 password grant | The endpoint consumes `x-www-form-urlencoded`, not JSON — a JSON body silently yields 422 |
| Login by email or nickname | One form field resolves to two different lookup paths |
| User enumeration resistance | An unknown account and a wrong password are asserted to produce *identical* responses, not merely unsuccessful ones |
| Blank credentials | Rejected before authentication runs |

### Refresh-token rotation

Refresh tokens are single-use: the presented token is consumed atomically in
Redis before a new pair is issued. The suite asserts that

- a refresh returns a token pair whose refresh token **differs** from the one spent,
- replaying a spent token is refused with 401 — the guarantee that makes rotation
  more than cosmetic,
- a rotated token can itself be rotated, so an ongoing session survives,
- malformed and absent tokens are refused rather than parsed.

### Force logout

"Sign out everywhere" records a cut-off timestamp; every token minted before
that instant is refused from then on. Covered:

- the access token that *triggered* the logout stops working — sparing the
  caller's own credential would leave the compromised session alive on the very
  device the user was trying to protect,
- refresh tokens issued before the cut-off can no longer be exchanged,
- a session opened on a second device is revoked too,
- logging in again restores access, so the cut-off is not a permanent lockout.

### Token validation

- A tampered payload is rejected: a signature that does not verify must never
  grant access.
- A refresh token is refused where an access token is expected. The two live
  for very different durations, so making them interchangeable would erase the
  point of short sessions.

### Optimistic locking

`GET /users/me` returns a `version` counter; `PATCH /users/me` requires it back
in `If-Match`. Without the lock, two clients that read the same profile and both
write produce a silent lost update.

| Case | Expected |
|---|---|
| Current version | 200, version advances by exactly one |
| Stale version | 412 — and the record is asserted to be *unchanged*, because a refusal that still wrote would be worse than no lock at all |
| Header absent | 428, distinct from a malformed request |
| Non-integer version | 400 |
| Version from the future | 412 — the check is equality, not ordering |
| Quoted form `"3"` | Accepted, per the `If-Match` syntax |
| After a force logout | Version **unchanged** — the auth cut-off and the lock counter are deliberately independent, so signing out of a lost phone does not invalidate an edit in progress on a laptop |

## A defect the suite found

`force-logout` compared a token's `iat` claim against the cut-off with a strict
`<`. JWT records `iat` in whole seconds, so a token minted in the same second as
the logout sat exactly on the boundary and survived.

The symptom was a test that passed or failed depending on whether two calls
landed in the same second. Confirming the cause meant separating the timestamps
deliberately; the fix was to make the comparison inclusive. The narrow window
matters because it covers precisely the case the feature exists for — a user who
realises immediately after signing in that the device is not theirs.

## Running locally

Requires a reachable API instance and an account that already exists in its
database.

```bash
mvn test "-Dapi.base.url=https://split-ly.com" \
         "-Dseed.user.email=you@example.com" \
         "-Dseed.user.password=yourpassword"
```

Credentials are never committed. `config.properties` holds placeholders; real
values come from the command line or from a git-ignored `local.properties`.

### Report

```bash
mvn clean test
allure serve target/allure-results
```

Each test carries its full HTTP exchange as an attachment, so a failure can be
diagnosed from the report alone.

## AI-assisted failure triage

A JUnit 5 extension captures a failing assertion together with the last HTTP
exchange and asks a locally running model to classify the failure: a product
defect, an environment problem, a flaky test, or a defect in the test itself.
The verdict is attached to the test's own card in the Allure report, next to the
request and response that produced it.

```bash
mvn test "-Dai.triage=true"
```

Requires [Ollama](https://ollama.com) with `llama3.2:3b`.

**The layer cannot influence a result.** It is implemented as a `TestWatcher`,
an interface invoked after the runner has already recorded the outcome — so the
guarantee is structural rather than a matter of discipline. An exception handler
would have had the power to swallow a failure; a watcher does not.

Everything runs against a local model. No payload from the system under test
leaves the machine, and no API key is required — which is what makes the feature
safe to point at an API whose data would be unacceptable to send elsewhere.

### What the model got wrong, and why that mattered

The first version reported `ENVIRONMENT` for a failure whose own explanation read
"a defect in the test itself". The parser scanned the whole reply for a category
name and found `environment` inside a phrase that *ruled it out*. It now reads
only the line that declares the verdict.

The second version had no HTTP context to reason about — the extension passed
nulls — so the model invented one. Adding a REST Assured filter that records the
last exchange fixed the verdicts; the filter redacts credentials, since a triage
prompt must not carry anything that grants access.

The third version called a successful login a product defect, because it treated
the test's expectation as the authoritative contract. The prompt now states
explicitly that an expectation is a claim which may itself be wrong.

## What CI verifies

The pipeline compiles the suite on every push. It does **not** execute the tests:
they need a live backend with seeded data, and a runner that cannot reach one
would produce either a permanently red badge or green mocks that assert nothing.
Running against a containerised environment is the next step.

## Layout

```
src/test/java/com/budgetapp/tests/
├── ai/          triage extension, local model client, exchange recorder
├── api/         endpoint clients — AuthApi, UsersApi
├── config/      configuration and shared request specifications
└── tests/       the suite itself
```

Tests never build requests inline. Every call goes through a client annotated
with `@Step`, so the report reads as a sequence of named actions rather than a
single opaque block.
