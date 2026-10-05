package app.sprout.rewards.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RewardsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
