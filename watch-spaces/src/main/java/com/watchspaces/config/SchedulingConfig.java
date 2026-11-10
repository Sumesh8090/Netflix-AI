package com.watchspaces.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** One shared multi-thread scheduler for @Scheduled tickers, reconnect/host-failover timers and vote timers. */
@Configuration
public class SchedulingConfig {
    @Bean(name = "taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("ws-sched-");
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
