package app.sprout.rewards.domain;

import app.sprout.rewards.config.RewardsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * What rewards need from the rest of Sprout: when a customer's account opened (accounts), and their
 * habit picture (habits: vested and pending points, months invested). Each call has a hard deadline.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(6);   // habits reads a year of history per call
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The points and months a customer's habit picture shows. */
    public record Habit(int vested, int pending, int monthsInvested) {}

    private final RewardsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Upstreams(RewardsProperties props, ObjectMapper json, Onward onward) {
        this.props = props;
        this.json = json;
        this.onward = onward;
    }

    /** The day the customer's account opened; NO_ACCOUNT if they have none. */
    public LocalDate opened(UUID user) {
        JsonNode a = get(HttpRequest.newBuilder(URI.create(props.accountsUrl() + "/internal/v1/accounts/" + user))
                .header("X-Service-Key", props.serviceKey()));
        return OffsetDateTime.parse(a.path("openedAt").asText()).atZoneSameInstant(IST).toLocalDate();
    }

    public Habit habit(UUID user) {
        JsonNode h = get(HttpRequest.newBuilder(URI.create(props.habitsUrl() + "/v1/habits/me")).header("X-User-Id", user.toString()));
        return new Habit(h.path("points").path("vested").asInt(), h.path("points").path("pending").asInt(),
                h.path("level").path("monthsInvested").asInt());
    }

    private JsonNode get(HttpRequest.Builder req) {
        onward.headers(req);
        try {
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() == 404) {
                throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
            }
            if (res.statusCode() != 200) {
                throw unavailable();
            }
            return json.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable();
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout isn't reachable right now. Try again shortly.", 5, Map.of());
    }
}
