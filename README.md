# csp-ticketing-api

> Ticketing & Fulfillment bounded context: service API

Part of the **CineSync Platform** distributed system.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa-promote/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `csp-docs`.
## Contact projection (HU-TICKETING-001)

The service keeps a local `userId -> email` projection fed by two Auth events, published by the
`csp-worker` relay on the `cine.events` topic exchange. It never reads the `auth` schema.

| Event | Routing key | Queue | Dead-letter queue |
|---|---|---|---|
| `UserRegistered` | `cine.auth.user-registered.v1` | `cine.ticketing_fulfillment.user-registered` | `cine.ticketing_fulfillment.user-registered.dlq` |
| `UserEmailChanged` | `cine.auth.user-email-changed.v1` | `cine.ticketing_fulfillment.user-email-changed` | `cine.ticketing_fulfillment.user-email-changed.dlq` |

- The queues, their bindings and the dead-letter queues are declared at start-up, before the relay is enabled.
- Both events are an upsert by `user_id`; an event only changes the contact if its `occurredAt` is newer than `last_updated_at`.
- The `eventId` is stored in `inbox_event` in the same transaction, so a redelivery has no effect.
- A message that is not valid JSON, or lacks `eventId`, `occurredAt`, `payload.userId` or a valid `payload.email`, is dead-lettered at once. Any other failure is retried (attempt 2 after 5 s, attempt 3 after 25 s) and then dead-lettered.

## Authentication (norm 5.3.7, ADR-042, ADR-056)

The service validates every token by itself with the public key of the identity service: RS256 only (`none`, HS256 and any other
algorithm are rejected), `exp` and `sub` required, 30 s of clock skew. It starts only with the key.

| Variable | Meaning |
|---|---|
| `JWT_PUBLIC_KEY` | PEM of the public key, on one line with `\n` or with line breaks |
| `JWT_PUBLIC_KEY_FILE` | Path of a file with the PEM, when the platform mounts it instead (one of the two is required) |
| `SERVICE_SUBJECTS` | Subjects of the service credentials that may call the internal operations; `csp-worker` by default |

| Routes | Who may call | Rejection |
|---|---|---|
| `/api/v1/ticketing/health`, `/health/ready` | anyone | none |
| `/api/v1/ticketing/me/**` | a user token (its `sub` is the user id) with the permission `ticket:read:self` | `401`; `403` without the permission |
| `/internal/**` | a service credential: issuer `csp-auth`, audience `csp-internal`, `sub` in `SERVICE_SUBJECTS` | `401` for any other token (a user token is never a service credential); `403` for a valid credential of another subject |

A rejection answers the single error envelope (`UNAUTHORIZED` or `FORBIDDEN`) and never reaches a controller. Everything else, such as
`/actuator/**`, needs a user token.

## Tests

```bash
mvn -B verify
```

The integration tests use Testcontainers (PostgreSQL 16 and RabbitMQ 3.13), so Docker must be running.

The tests apply the migrations of `csp-ticketing-db` (Flyway, same settings as that repository), so clone it next to this one (`../csp-ticketing-db`) or pass `-Dticketing.db.dir=<path to its sql folder>`; the build fails with that message if the folder is missing.
