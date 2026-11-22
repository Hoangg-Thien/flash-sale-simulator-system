package com.hthien.flash_sale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.redisson.config.Config;
import org.redisson.Redisson;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean

    public RedissonClient redissonClient(){

        Config config = new Config();

        // single server mode; production dùng sentinel hoặc cluster mode
        config.useSingleServer()
        .setAddress("redis://" + redisHost + ":" + redisPort)
        .setConnectionPoolSize(10) // pool size
        .setConnectionMinimumIdleSize(5) // minimum idle connections
        .setConnectTimeout(5000) // 5s connect timeout
        .setTimeout(3000); // 3s command timeout

        log.info("Configuring Redisson client: redis://{}:{}", redisHost, redisPort);
        return Redisson.create(config);
    }
}
