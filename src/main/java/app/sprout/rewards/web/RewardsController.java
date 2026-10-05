package app.sprout.rewards.web;

import app.sprout.rewards.domain.ApiException;
import app.sprout.rewards.domain.ErrorCode;
import app.sprout.rewards.domain.Rewards;
import app.sprout.rewards.domain.Rewards.Balance;
import app.sprout.rewards.domain.Rewards.Created;
import app.sprout.rewards.domain.Rewards.Redemption;
import app.sprout.rewards.domain.Rewards.Referrals;
import app.sprout.rewards.domain.Vault;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The rewards API (rewards-v1.yaml): the vault, redemptions and referrals. */
@RestController
public class RewardsController {

    public record RedeemRequest(String itemCode) {}

    public record ClaimRequest(String code) {}

    private final Rewards rewards;

    public RewardsController(Rewards rewards) {
        this.rewards = rewards;
    }

    @GetMapping("/v1/vault")
    public Map<String, Object> vault(@RequestHeader(value = "X-User-Id", required = false) String user) {
        Balance b = rewards.balance(userId(user));
        return Map.of("balance", Map.of("habitPoints", b.habitPoints(), "referralPoints", b.referralPoints(), "spent", b.spent(),
                        "available", b.available(), "pending", b.pending()),
                "items", Vault.ITEMS.stream().map(i -> Map.of("code", i.code(), "name", i.name(), "brand", i.brand(), "kind", i.kind(),
                        "points", i.points(), "affordable", b.available() >= i.points())).toList());
    }

    @PostMapping("/v1/redemptions")
    public ResponseEntity<Map<String, Object>> redeem(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody RedeemRequest req) {
        Created c = rewards.redeem(userId(user), key, req.itemCode());
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(redemption(c.redemption()));
    }

    @GetMapping("/v1/redemptions")
    public Map<String, Object> redemptions(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("redemptions", rewards.redemptions(userId(user)).stream().map(RewardsController::redemption).toList());
    }

    @GetMapping("/v1/referrals/me")
    public Map<String, Object> referrals(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return referrals(rewards.referrals(userId(user)));
    }

    @PostMapping("/v1/referrals/claim")
    public Map<String, Object> claim(@RequestHeader(value = "X-User-Id", required = false) String user, @RequestBody ClaimRequest req) {
        return referrals(rewards.claim(userId(user), req.code()));
    }

    static Map<String, Object> redemption(Redemption r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("itemCode", r.itemCode());
        m.put("name", r.name());
        m.put("points", r.points());
        if (r.voucherCode() != null) {
            m.put("voucherCode", r.voucherCode());
        }
        m.put("at", r.at().toString());
        return m;
    }

    static Map<String, Object> referrals(Referrals r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.code());
        if (r.referredBy() != null) {
            m.put("referredBy", r.referredBy());
        }
        m.put("friends", r.friends().stream().map(f -> Map.of("joinedOn", f.joinedOn().toString(), "monthsInvested", f.monthsInvested(),
                "rewarded", f.rewarded())).toList());
        m.put("pointsEarned", r.pointsEarned());
        return m;
    }

    private static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
        }
    }
}
