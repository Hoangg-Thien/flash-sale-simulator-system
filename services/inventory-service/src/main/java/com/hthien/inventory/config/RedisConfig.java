package com.hthien.inventory.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "inventory.lock-mode", havingValue = "REDIS")
class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    RedissonClient inventoryRedis(@Value("${inventory.redis-address:redis://localhost:6379}") String address) {
        var config = new Config().setLazyInitialization(true);
        config.useSingleServer().setAddress(address)
            .setConnectionMinimumIdleSize(2).setConnectionPoolSize(10)
            .setConnectTimeout(1000).setTimeout(1500).setRetryAttempts(0);
        return Redisson.create(config);
    }
}
