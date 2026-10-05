package app.sprout.rewards;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.rewards.domain.ApiException;
import app.sprout.rewards.domain.Rewards;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Rewards on a real Postgres against stand-ins for accounts and habits; every response checked against rewards-v1.yaml. */
@Testcontainers
@SpringBootTest(properties = "spring.config.name=rewards")
@AutoConfigureMockMvc
class RewardsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, String> OPENED = new ConcurrentHashMap<>();          // user -> account opened (date)
    static final Map<String, int[]> HABITS = new ConcurrentHashMap<>();           // user -> {vested, pending, monthsInvested}
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=rewards");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.rewards.accounts-url", () -> base);
        r.add("sprout.rewards.habits-url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T05:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.REWARDS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired Rewards rewards;

    /** A customer whose account opened on {@code opened}, with these habit points and months invested. */
    static UUID customer(String opened, int vested, int pending, int months) {
        UUID u = UUID.randomUUID();
        OPENED.put(u.toString(), opened);
        HABITS.put(u.toString(), new int[] {vested, pending, months});
        return u;
    }

    ResultActions as(UUID u, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("X-User-Id", u.toString());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions redeem(UUID u, String item, String key) throws Exception {
        return as(u, post("/v1/redemptions").header("Idempotency-Key", key), Map.of("itemCode", item));
    }

    @Test
    void theVaultShowsWhatVestedPointsBuy() throws Exception {
        UUID u = customer("2026-06-01", 1000, 150, 4);
        JsonNode v = body(as(u, get("/v1/vault"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(v.path("balance").path("available").asInt()).isEqualTo(1000);
        assertThat(v.path("balance").path("pending").asInt()).as("pending points can't be spent yet").isEqualTo(150);
        assertThat(v.path("items").findValuesAsText("brand")).allMatch(b -> b.equals("Sprout") || b.contains("(fictional)"));
        JsonNode metro = v.path("items").get(3);
        assertThat(metro.path("code").asText()).isEqualTo("CITY_METRO_100");
        assertThat(metro.path("affordable").asBoolean()).isTrue();
        assertThat(v.path("items").get(4).path("affordable").asBoolean()).as("1,800 points").isFalse();
        as(UUID.randomUUID(), get("/v1/vault"), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    @Test
    void redeemingSpendsOnceAndNeverMoreThanThereIs() throws Exception {
        UUID u = customer("2026-06-01", 1000, 0, 4);
        String key = UUID.randomUUID().toString();
        JsonNode r = body(redeem(u, "MONSOON_CHAI_50", key).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        assertThat(r.path("voucherCode").asText()).matches("SPR-[A-Z2-9]{4}-[A-Z2-9]{4}");
        redeem(u, "MONSOON_CHAI_50", key).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(r.path("id").asText()));
        assertThat(body(as(u, get("/v1/vault"), null)).path("balance").path("available").asInt()).isEqualTo(500);
        redeem(u, "BOOKWORM_200", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_POINTS"));
        redeem(u, "GOLD_BAR", UUID.randomUUID().toString()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        JsonNode theme = body(redeem(u, "THEME_MARIGOLD", UUID.randomUUID().toString()).andExpect(status().isCreated()));
        assertThat(theme.has("voucherCode")).as("a theme isn't a voucher").isFalse();
        as(u, get("/v1/redemptions"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.redemptions.length()").value(2));
    }

    @Test
    void redemptionsAtTheSameMomentCantSpendTheSamePointsTwice() throws Exception {
        UUID u = customer("2026-06-01", 900, 0, 4);
        List<Future<Boolean>> tries = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                tries.add(pool.submit(() -> {
                    try {
                        rewards.redeem(u, UUID.randomUUID().toString(), "MONSOON_CHAI_50");
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                }));
            }
        }
        int ok = 0;
        for (Future<Boolean> t : tries) {
            ok += t.get() ? 1 : 0;
        }
        assertThat(ok).as("900 points buy one ₹50 voucher").isEqualTo(1);
        assertThat(rewards.balance(u).available()).isEqualTo(400);
    }

    @Test
    void referralsRewardTheHabitNotTheSignUp() throws Exception {
        UUID asha = customer("2026-03-01", 0, 0, 7);
        UUID ravi = customer("2026-10-01", 0, 0, 0);
        String code = body(as(asha, get("/v1/referrals/me"), null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("code").asText();
        assertThat(code).matches("SPR-[A-Z2-9]{6}");
        JsonNode linked = body(as(ravi, post("/v1/referrals/claim"), Map.of("code", code.toLowerCase())).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT));
        assertThat(linked.path("referredBy").asText()).isEqualTo(code);
        JsonNode mine = body(as(asha, get("/v1/referrals/me"), null));
        assertThat(mine.path("friends").get(0).path("joinedOn").asText()).isEqualTo("2026-10-01");
        assertThat(mine.path("friends").get(0).path("rewarded").asBoolean()).as("signing up earns nothing").isFalse();
        assertThat(mine.path("pointsEarned").asInt()).isZero();

        HABITS.put(ravi.toString(), new int[] {0, 0, 3});   // Ravi has now invested in three different months
        JsonNode after = body(as(asha, get("/v1/referrals/me"), null));
        assertThat(after.path("friends").get(0).path("rewarded").asBoolean()).isTrue();
        assertThat(after.path("pointsEarned").asInt()).isEqualTo(500);
        assertThat(body(as(asha, get("/v1/vault"), null)).path("balance").path("referralPoints").asInt()).isEqualTo(500);
        assertThat(body(as(ravi, get("/v1/vault"), null)).path("balance").path("available").asInt()).as("both of them").isEqualTo(500);
    }

    @Test
    void aReferralCodeIsUsedOnceEarlyAndNeverOnYourself() throws Exception {
        UUID asha = customer("2026-03-01", 0, 0, 7);
        UUID ravi = customer("2026-10-01", 0, 0, 0);
        UUID old = customer("2026-08-01", 0, 0, 2);
        String code = body(as(asha, get("/v1/referrals/me"), null)).path("code").asText();
        as(asha, post("/v1/referrals/claim"), Map.of("code", code)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REFERRAL"));
        as(ravi, post("/v1/referrals/claim"), Map.of("code", "SPR-NOSUCH")).andExpect(status().isUnprocessableEntity());
        as(old, post("/v1/referrals/claim"), Map.of("code", code)).andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT);
        as(ravi, post("/v1/referrals/claim"), Map.of("code", code)).andExpect(status().isOk());
        as(ravi, post("/v1/referrals/claim"), Map.of("code", code)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REFERRED"));
    }

    // ── stand-ins ────────────────────────────────────────────────────────────

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Accounts (when an account opened) and habits (points and months invested). */
    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/accounts/", ex -> {
                String opened = OPENED.get(ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length()));
                reply(ex, opened == null ? 404 : 200, opened == null ? Map.of("code", "NO_ACCOUNT")
                        : Map.of("status", "ACTIVE", "openedAt", opened + "T04:00:00Z"));
            });
            s.createContext("/v1/habits/me", ex -> {
                int[] h = HABITS.get(ex.getRequestHeaders().getFirst("X-User-Id"));
                reply(ex, h == null ? 404 : 200, h == null ? Map.of("code", "NO_ACCOUNT")
                        : Map.of("points", Map.of("vested", h[0], "pending", h[1], "forfeited", 0), "level", Map.of("monthsInvested", h[2])));
            });
            s.setExecutor(Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
