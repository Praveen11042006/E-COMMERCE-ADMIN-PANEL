# Phase 12A Result — Transactional Integrity Hardening

## Original defects and root causes

1. Checkout read, validated, decremented, and saved stock without locking the
   product rows. Concurrent requests could both observe the same available
   quantity and oversell it.
2. Payment creation checked for an existing payment without serializing
   competing requests for the same order. Two requests could both pass the
   check and create successful payments.
3. Coupon eligibility and its `usedCount` increment were separate from a
   concurrency guard. Concurrent checkouts could both consume the final use.

## Implementation

- Checkout remains transactional and locks all requested products with
  `PESSIMISTIC_WRITE`, ordered by product ID before stock validation and
  decrement. Repeated product IDs are aggregated before locking.
- Payment processing locks the order row with `PESSIMISTIC_WRITE` before
  checking for an existing payment and creating a new one.
- Coupon reservation locks the coupon row with `PESSIMISTIC_WRITE`, validates
  active/expiry/minimum/usage-limit conditions, then increments `usedCount`
  within the transaction. Checkout uses this reservation method.
- No API contracts, mock payment behavior, JWT behavior, configurations,
  Flyway migration, database schema, or production database settings changed.

## Files changed for this phase

- `springapp/src/main/java/com/examly/springapp/service/OrderService.java`
- `springapp/src/main/java/com/examly/springapp/service/PaymentService.java`
- `springapp/src/main/java/com/examly/springapp/service/CouponService.java`
- `springapp/src/main/java/com/examly/springapp/repository/ProductRepository.java`
- `springapp/src/main/java/com/examly/springapp/repository/OrderRepository.java`
- `springapp/src/main/java/com/examly/springapp/repository/CouponRepository.java`
- `springapp/src/test/java/com/examly/springapp/service/TransactionalIntegrityConcurrencyTest.java`

## Regression coverage

The focused H2 test class contains five tests:

1. Two concurrent checkouts for the final stock unit result in one order and
   no negative stock.
2. A successful checkout still creates its order and consumes stock.
3. Two concurrent payment attempts for one order result in one payment.
4. Two concurrent uses of a coupon with one remaining use do not exceed its
   usage limit.
5. A failed checkout leaves stock, coupon usage, and orders unchanged.

Existing order, payment, and coupon regression tests remain part of the full
suite.

## Measured verification

Command run from `springapp`:

```text
mvn test
```

Result: **BUILD SUCCESS** — 116 tests, 0 failures, 0 errors, 0 skipped.
The focused `TransactionalIntegrityConcurrencyTest` ran 5 tests with 0
failures and 0 errors. Tests used the existing H2 test configuration; no
MySQL database, original `ecom` database, production database, or Flyway
operation was used.

## Remaining risks and migration requirements

- H2 locking results do not prove identical MySQL/InnoDB behavior. Run the
  same concurrency scenarios against an isolated MySQL staging copy before
  production deployment.
- The order-row lock serializes payment creation in this application. A
  database-level unique constraint on `payments.order_id` would provide an
  additional cross-writer safeguard, but adding it requires a separately
  approved schema migration and was not performed.
- Pessimistic locking can wait or deadlock under load. The consistent ascending
  product-ID lock order reduces checkout deadlock risk; production monitoring
  and safe retry policy decisions remain operational work.

## Acceptance criteria

| Criterion | Status |
| --- | --- |
| Tested final-stock checkout does not oversell | PASS |
| Tested repeated payment creates one payment | PASS |
| Tested coupon use cannot exceed final usage | PASS |
| Tested failed checkout leaves relevant state unchanged | PASS |
| Existing backend regression suite passes | PASS |
| No production/original database access | PASS |
| No unapproved schema change or Flyway operation | PASS |

Phase 12A is complete. Phase 12B was not started.
