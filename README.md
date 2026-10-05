# sprout-rewards

**What habit points buy, and rewards for friends who invest.**

- **Points come from the habit.** What a customer can spend is their **vested** points from
  [habits](https://github.com/SaiNayakk/sprout-habits) (earned by investing month after month, kept only if
  the money stays invested), plus referral rewards, less what they have spent. It is worked out each
  time, never stored, so it can't drift from their trading history.
- **The vault**: vouchers from fictional brands (the demo has no real partners), an app theme, a tree
  planted. A redemption is decided under a per-customer lock, so two at the same moment can't spend the
  same points, and repeating one with the same `Idempotency-Key` returns the first.
- **Referrals reward the habit, not the sign-up.** A new customer can enter a friend's code in their
  first 30 days (once, never their own). Nothing is earned for signing up: once the new customer has
  invested in 3 different months, both get 500 points.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`rewards-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/rewards-v1.yaml)
in sprout-contracts. It runs inside the **trading** host, next to habits.

`./mvnw verify` runs the tests on a real Postgres against stand-ins for accounts and habits, including
five redemptions at the same moment against points enough for one.

## License

MIT
