package app.sprout.rewards.domain;

import app.sprout.rewards.config.RewardsProperties;
import app.sprout.rewards.domain.Upstreams.Habit;
import app.sprout.rewards.domain.Vault.Item;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spending habit points, and referrals. What a customer can spend is their vested habit points plus
 * referral rewards, less what they have spent: worked out each time, never stored, so it can't drift
 * from their history. A redemption is decided under a per-customer lock, so two at once can't spend the
 * same points.
 */
@Service
public class Rewards {

    /** Letters and digits that can't be mistaken for each other. */
    private static final char[] CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Balance(int habitPoints, int referralPoints, int spent, int available, int pending) {}

    public record Redemption(UUID id, String itemCode, String name, int points, String voucherCode, Instant at) {}

    public record Created(Redemption redemption, boolean created) {}

    public record Friend(LocalDate joinedOn, int monthsInvested, boolean rewarded) {}

    public record Referrals(String code, String referredBy, List<Friend> friends, int pointsEarned) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Upstreams up;
    private final RewardsProperties props;

    public Rewards(JdbcClient db, TransactionTemplate tx, Clock clock, Upstreams up, RewardsProperties props) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.up = up;
        this.props = props;
    }

    // ── the balance and the vault ────────────────────────────────────────────

    public Balance balance(UUID user) {
        Habit h = up.habit(user);
        settleReferrals(user);
        return balance(user, h);
    }

    private Balance balance(UUID user, Habit h) {
        int referral = props.referralPoints() * db.sql(
                        "SELECT COUNT(*) FROM referrals WHERE rewarded_at IS NOT NULL AND (referrer_user = ? OR referred_user = ?)")
                .params(user, user).query(Integer.class).single();
        int spent = db.sql("SELECT COALESCE(SUM(points), 0) FROM redemptions WHERE user_id = ?").param(user).query(Integer.class).single();
        return new Balance(h.vested(), referral, spent, Math.max(0, h.vested() + referral - spent), h.pending());
    }

    public Created redeem(UUID user, String key, String itemCode) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
        Optional<Redemption> earlier = byKey(user, key);
        if (earlier.isPresent()) {
            return new Created(earlier.get(), false);
        }
        Item item = Vault.item(itemCode == null ? "" : itemCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "There's nothing called " + itemCode + " in the vault."));
        Habit h = up.habit(user);
        settleReferrals(user);
        UUID id = UUID.randomUUID();
        try {
            return tx.execute(s -> {
                // one redemption at a time per customer: the balance below can't be spent twice
                db.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param("rewards:" + user).query().singleRow();
                Optional<Redemption> raced = byKey(user, key);
                if (raced.isPresent()) {
                    return new Created(raced.get(), false);
                }
                Balance b = balance(user, h);
                if (b.available() < item.points()) {
                    throw new ApiException(ErrorCode.NOT_ENOUGH_POINTS, "That needs " + item.points() + " points; you have " + b.available()
                            + " to spend" + (b.pending() > 0 ? " (" + b.pending() + " more vest as your investments stay invested)." : "."));
                }
                String voucher = item.kind().equals("VOUCHER") ? "SPR-" + code(4) + "-" + code(4) : null;
                db.sql("INSERT INTO redemptions (id, user_id, idempotency_key, item_code, points, voucher_code, at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                        .params(id, user, key, item.code(), item.points(), voucher, Timestamp.from(clock.instant())).update();
                return new Created(byKey(user, key).orElseThrow(), true);
            });
        } catch (DuplicateKeyException e) {
            return new Created(byKey(user, key).orElseThrow(), false);
        }
    }

    public List<Redemption> redemptions(UUID user) {
        return db.sql(REDEMPTION_SQL + " WHERE user_id = ? ORDER BY at DESC LIMIT 100").param(user).query(Rewards::redemption).list();
    }

    // ── referrals ────────────────────────────────────────────────────────────

    public Referrals referrals(UUID user) {
        up.opened(user);
        settleReferrals(user);
        String code = myCode(user);
        String referredBy = db.sql("SELECT code FROM referrals WHERE referred_user = ?").param(user).query(String.class).optional().orElse(null);
        List<Friend> friends = db.sql("SELECT referred_user, joined_on, rewarded_at FROM referrals WHERE referrer_user = ? ORDER BY claimed_at")
                .param(user)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getTimestamp(3)})
                .list().stream()
                .map(r -> new Friend((LocalDate) r[1], r[2] != null ? props.referralMonths() : monthsOf((UUID) r[0]), r[2] != null))
                .toList();
        int earned = props.referralPoints() * db.sql(
                        "SELECT COUNT(*) FROM referrals WHERE rewarded_at IS NOT NULL AND (referrer_user = ? OR referred_user = ?)")
                .params(user, user).query(Integer.class).single();
        return new Referrals(code, referredBy, friends, earned);
    }

    /** Links a new customer to the friend whose code they entered. */
    public Referrals claim(UUID user, String codeInput) {
        LocalDate opened = up.opened(user);
        String code = codeInput == null ? "" : codeInput.trim().toUpperCase(Locale.ROOT);
        UUID referrer = db.sql("SELECT user_id FROM referral_codes WHERE code = ?").param(code).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFERRAL, "That isn't a Sprout referral code."));
        if (referrer.equals(user)) {
            throw new ApiException(ErrorCode.INVALID_REFERRAL, "That's your own code; share it with a friend instead.");
        }
        if (opened.plusDays(props.referralWindow().toDays()).isBefore(LocalDate.now(clock.withZone(Upstreams.IST)))) {
            throw new ApiException(ErrorCode.INVALID_REFERRAL, "A friend's code can be entered in your first "
                    + props.referralWindow().toDays() + " days with Sprout.");
        }
        try {
            db.sql("INSERT INTO referrals (referred_user, referrer_user, code, joined_on, claimed_at) VALUES (?, ?, ?, ?, ?)")
                    .params(user, referrer, code, java.sql.Date.valueOf(opened), Timestamp.from(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ALREADY_REFERRED, "A friend is already linked to your account.");
        }
        return referrals(user);
    }

    /**
     * Rewards referrals this customer is part of (either side) whose new customer has now invested in
     * enough different months. Worked out when someone looks; rewarding is a one-way, once-only step.
     */
    void settleReferrals(UUID user) {
        List<UUID> waiting = db.sql("SELECT referred_user FROM referrals WHERE rewarded_at IS NULL AND (referrer_user = ? OR referred_user = ?)")
                .params(user, user).query(UUID.class).list();
        for (UUID friend : waiting) {
            if (monthsOf(friend) >= props.referralMonths()) {
                db.sql("UPDATE referrals SET rewarded_at = ? WHERE referred_user = ? AND rewarded_at IS NULL")
                        .params(Timestamp.from(clock.instant()), friend).update();
            }
        }
    }

    private int monthsOf(UUID friend) {
        try {
            return up.habit(friend).monthsInvested();
        } catch (ApiException e) {
            return 0;   // their history can't be read now: not rewarded yet, asked again next time
        }
    }

    private String myCode(UUID user) {
        Optional<String> existing = db.sql("SELECT code FROM referral_codes WHERE user_id = ?").param(user).query(String.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                db.sql("INSERT INTO referral_codes (user_id, code, created_at) VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING")
                        .params(user, "SPR-" + code(6), Timestamp.from(clock.instant())).update();
                return db.sql("SELECT code FROM referral_codes WHERE user_id = ?").param(user).query(String.class).single();
            } catch (DuplicateKeyException e) {
                // another customer has that code: draw again
            }
        }
        throw new IllegalStateException("No free referral code after 5 tries");
    }

    private static String code(int length) {
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            b.append(CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)]);
        }
        return b.toString();
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private Optional<Redemption> byKey(UUID user, String key) {
        return db.sql(REDEMPTION_SQL + " WHERE user_id = ? AND idempotency_key = ?").params(user, key).query(Rewards::redemption).optional();
    }

    private static final String REDEMPTION_SQL = "SELECT id, item_code, points, voucher_code, at FROM redemptions";

    private static Redemption redemption(ResultSet rs, int n) throws SQLException {
        String code = rs.getString("item_code");
        return new Redemption(rs.getObject("id", UUID.class), code, Vault.item(code).map(Item::name).orElse(code), rs.getInt("points"),
                rs.getString("voucher_code"), rs.getTimestamp("at").toInstant());
    }
}
