package com.vulnwatch.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Map;

@Slf4j
@Configuration
public class RedisConfig {

  public static final class Keys {
    public static final String SCAN_QUEUE = "scan-jobs";
    public static final String SURFACE_RESULT_STREAM = "surface:result:stream";
    public static final String RETRY_ZSET = "scan:retry";
    public static final String DEAD_LETTER_LIST = "scan:dead-letter";
    public static final String SCAN_RESULTS_LIST = "scan:results";

    private Keys() {}
  }


  public static final String CONSUMER_GROUP = "worker-group";

  @Bean
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper()));
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper()));
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  public DefaultRedisScript<Long> popAndRetryScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("lua/pop_and_retry.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  public ApplicationRunner initConsumerGroup(RedisTemplate<String, Object> redisTemplate) {
    return args -> {
      try {
        redisTemplate.opsForStream().createGroup(
                Keys.SURFACE_RESULT_STREAM,
                ReadOffset.latest(),
                CONSUMER_GROUP
        );
        log.info("Created consumer group '{}' on stream '{}'", CONSUMER_GROUP, Keys.SURFACE_RESULT_STREAM);
      } catch (RedisSystemException e) {
        if (e.getMessage() != null && e.getMessage()
                .contains("ERR The OBJECT key name does not exist")) {

          log.info("Stream doesn't exist, creating dummy entry first...");
          MapRecord<String, Object, Object> dummy = MapRecord.create(Keys.SURFACE_RESULT_STREAM, Map.of("init", "true"));
          redisTemplate.opsForStream()
                  .add(dummy);
          redisTemplate.opsForStream()
                  .createGroup(
                  Keys.SURFACE_RESULT_STREAM,
                  ReadOffset.latest(),
                  CONSUMER_GROUP
          );
          log.info("Stream created and consumer group registered");
        } else {
          log.info("Consumer group already exists or stream already initialised: {}", e.getMessage());
        }
      }
    };
  }
}