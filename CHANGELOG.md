# Changelog — csp-ticketing-api

## [Unreleased]

### Fixed
- Consumer resilience (HU-TICKETING-001, `csp-ticketing-api#7`): when the broker cancels a consumer or deletes its queue, the listener container declares the queues, dead-letter queues and bindings again and subscribes again with exponential backoff (1 s doubling up to 30 s, no attempt limit). A WARN names the queue (never the payload) and an INFO plus the `ticketing.consumer.resubscriptions` counter record the resubscription.
- `/health/ready` reported the consumers through a bean that looked for listener containers among the Spring beans, where `@RabbitListener` containers are not; the `consumers` indicator now reads them from the listener registry and is DOWN while any container has no subscribed consumer.
- The booking-confirmed, concession-order-confirmed and ticket-issued queues and their bindings were built but never declared; they are now part of the declared topology.
- `application.yml` declared `spring.rabbitmq` twice, which Spring Boot rejects at start-up; the connection settings are merged into one block.
- `Ticket` JSON columns (`seat_labels_snapshot`, `concessions_snapshot`) are mapped with `@JdbcTypeCode(SqlTypes.JSON)`, without which the entity manager failed to build.
- The test sources compile again (a duplicated variable in `ConcessionOrderConfirmedConsumerTest`; `ConsumerResilienceTest` called an API that does not exist and is removed, its resilience scenarios return with HU-TICKETING-001 issue #7).
- Auth event queues are bound to the contract routing keys `cine.auth.user-registered.v1` and `cine.auth.user-email-changed.v1` (they were declared but never bound, and with the wrong keys), and are named `cine.ticketing_fulfillment.*`, each with its own `.dlq` through `cine.dlx`.
- Listeners acknowledge automatically (manual mode with a `void` listener never acknowledged), retries follow ADR-007 (5 s, then 25 s) and no longer run twice, and rejected messages are dead-lettered through `cine.dlx`.
- Contact emails are no longer logged.

### Added
- HU-TICKETING-001: the contact projection applies only events newer than the stored `last_updated_at`, normalizes the email to lower case, and validates the Auth event envelope; invalid messages go straight to the dead-letter queue.
- Unit, contract and integration tests (PostgreSQL and RabbitMQ with Testcontainers) for `UserRegistered` and `UserEmailChanged`.
