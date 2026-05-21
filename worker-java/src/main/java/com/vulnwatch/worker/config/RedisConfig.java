package com.vulnwatch.worker.config;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;

public class RedisConfig {

    private static final JedisPooled client;

    static {
        String host     = requireEnv("REDIS_HOST");
        int    port     = Integer.parseInt(requireEnv("REDIS_PORT"));
        String password = requireEnv("REDIS_PASSWORD");

        HostAndPort hostAndPort = new HostAndPort(host, port);

        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(true)
                .password(password)
                .socketTimeoutMillis(5000)
                .connectionTimeoutMillis(3000)
                .build();

        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);

        client = new JedisPooled(poolConfig, hostAndPort, clientConfig);
    }

    public static JedisPooled getClient() {
        return client;
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required environment variable not set: " + key
            );
        }
        return value;
    }
}