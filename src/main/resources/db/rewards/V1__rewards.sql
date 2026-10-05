-- Rewards keeps only what customers chose (redemptions, referral codes and links). The habit points
-- they spend are worked out by habits from their trading history each time.

CREATE TABLE redemptions (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL,
    idempotency_key text NOT NULL,
    item_code       text NOT NULL,
    points          int NOT NULL CHECK (points > 0),
    voucher_code    text,
    at              timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX redemptions_by_user ON redemptions (user_id, at DESC);

CREATE TABLE referral_codes (
    user_id    uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL
);

-- Who brought whom. rewarded_at is set once the new customer has invested in enough different months.
CREATE TABLE referrals (
    referred_user uuid PRIMARY KEY,
    referrer_user uuid NOT NULL,
    code          text NOT NULL,
    joined_on     date NOT NULL,
    claimed_at    timestamptz NOT NULL,
    rewarded_at   timestamptz
);
CREATE INDEX referrals_by_referrer ON referrals (referrer_user);
