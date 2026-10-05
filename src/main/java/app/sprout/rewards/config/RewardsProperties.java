package app.sprout.rewards.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.rewards} in rewards.yml. */
@ConfigurationProperties("sprout.rewards")
public record RewardsProperties(String serviceKey, int referralPoints, int referralMonths, Duration referralWindow, String accountsUrl,
                                String habitsUrl) {}
