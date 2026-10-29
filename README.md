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

## Tests

```bash
mvn -B verify
```

The integration tests use Testcontainers (PostgreSQL 16 and RabbitMQ 3.13), so Docker must be running.
