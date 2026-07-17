# ADR-0001 — Cart persistence and event publishing

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Kamesh Chathuranga
- **Scope:** Cart bounded context.
- **Supersedes / relates to:** the platform invariant *"Transactional Outbox + CDC (Debezium) is the sole event-publishing mechanism."*

## Context and problem statement

Cart's mandated store is **DynamoDB** — predictable single-digit-millisecond KV access, per-key horizontal scale, multi-region as a prod
target. This is the correct store for a write-hot, owner-scoped KV workload.

The platform also mandates a single event-publishing mechanism: **Transactional Outbox drained by Debezium CDC**, chosen specifically to
forbid dual-writes.

These two rules cannot both hold for Cart. Debezium's stable source-connector portfolio
(MongoDB, MariaDB, MySQL, PostgreSQL, SQL Server, Oracle, Db2, Cassandra, Vitess *incubating*, Spanner) **contains no DynamoDB connector**,
incubating
or otherwise. Cart-on-DynamoDB therefore cannot be drained by the mechanism the platform assumes.

We must resolve the contradiction without (a) re-introducing a dual-write, (b) putting Cart on the wrong store for its workload, or (c)
re-entering the Connect-image packaging risk already paid down once (in-cluster Strimzi/Kaniko builds → NAT exhaustion; resolved via
CI-produced, digest-pinned GHCR images).

## Decision drivers

- No dual-writes. The store write and the event must commit atomically.
- Keep Cart on a store appropriate to its workload (write-hot KV), not one chosen for our convenience.
- Do not add new cluster infrastructure or a new Connect source connector.
- **Local ≡ prod behavioral parity.** The mechanism must behave identically on `dynamodb-local`/LocalStack and on real DynamoDB.
- No correctness-critical consumer depends on cart-event latency (verified: all cart-event consumers — Notification, Analytics,
  Recommendation — are analytics-grade; Checkout reads the cart **synchronously** and does not wait on a cart event).

## Considered options

### Option A — DynamoDB + DynamoDB Streams as the CDC transport

Streams is AWS's canonical CDC path: managed, per-key ordered, and captures TTL deletions natively.
**Rejected.** No Debezium connector exists, so draining Streams into Kafka requires a hand-rolled or vendor-licensed Kafka Connect source
connector packaged into the Strimzi Connect image — the exact JAR-hell / Kaniko class of risk already resolved once on this project. Locally
it also leans on Streams emulation, the weakest surface of LocalStack. Highest risk, and a *known* risk profile.

### Option B — MongoDB (Percona PSMDB), reusing the Catalog pipeline wholesale

Zero new infrastructure: Debezium Mongo 3.5.2 already in the Connect image, `MongoEventRouter` outbox SMT already configured, replica-set
multi-document transactions give a genuine atomic cart-write + outbox-write.
**Rejected.** Optimizes for *our* convenience, not the workload. Cart is write-hot KV; a document DB with multi-document transactions is a
worse fit than a KV store at Amazon/Temu write rates. Correct *local* answer, wrong *scale* answer. Would be a conscious architectural
downgrade for local convenience — explicitly disallowed by project rules.

### Option C — DynamoDB + in-table outbox + sharded polling-publisher relay — **chosen**

- Cart item and its outbox items are written in a single `TransactWriteItems` (one table, one account/region). This is a **true
  transactional outbox** — one atomic write, no dual-write.
- A **polling publisher** relay (Richardson's canonical polling-outbox relay, not a shortcut) claims outbox shards via conditional writes,
  publishes to Kafka, then deletes. Sharded across N partitions → horizontal scale with no leader election. Per-key ordering preserved by
  shard affinity on `cartId`.

## Decision outcome

**Chosen: Option C.**

It is the only option that keeps the correct store, keeps the local environment behaviourally honest, and does not re-enter the
Connect-image packaging swamp. The added mechanism is a *relay transport*, not new *infrastructure*.

### Consequences

**Positive**

- No dual-write; atomic cart + outbox commit via `TransactWriteItems`.
- Zero new cluster infrastructure, zero new Connect source connector, no Streams emulation dependency.
- **Local ≡ prod:** the relay behaves identically against `dynamodb-local`/LocalStack and real DynamoDB, eliminating local/prod divergence
  at the mechanism level.
- Reuses the Catalog LWW/ECST reconciliation playbook unchanged.

**Negative / accepted**

- A second event-publishing *mechanism* now exists platform-wide (relay-based alongside CDC-based). Accepted; recorded here as deliberate.
- Adds ~1s publish latency versus CDC. Accepted — no correctness-critical consumer exists on the cart topics; Checkout reads the cart
  synchronously.
- At-least-once delivery: relay crash mid-publish can duplicate. Accepted — all consumers are idempotent by platform mandate.
- **DynamoDB TTL deletion is silent under Option C** (no Streams to observe it), so TTL alone never emits a tombstone and the compacted
  state topic would grow unbounded. Mitigated by an application-managed reaper that emits `CartExpired` *through the outbox* before
  deletion; DynamoDB TTL retained only as a backstop.

## More information

- Verified 2026-07-16: Debezium 3.5 stable source connectors do not include DynamoDB.
- Relay design detail, shard-claim protocol, and lag metrics.
