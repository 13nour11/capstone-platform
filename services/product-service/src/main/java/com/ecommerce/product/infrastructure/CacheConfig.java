package com.ecommerce.product.infrastructure;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.ecommerce.product.application.PageResult;
import com.ecommerce.product.application.ProductDetails;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis cache-aside, shared by every replica. Values are typed JSON (no Java class names stored in Redis).
 * Evictions run only after the write transaction commits, and Redis errors fall back to the database.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String PRODUCT = "product";
    public static final String PRODUCTS = "products";

    @Bean
    RedisCacheManagerBuilderCustomizer productCaches(ObjectMapper objectMapper,
                                                     @Value("${product.cache.ttl:10m}") Duration ttl) {
        JavaType item = objectMapper.constructType(ProductDetails.class);
        JavaType page = objectMapper.getTypeFactory().constructParametricType(PageResult.class, ProductDetails.class);
        return builder -> builder
                .transactionAware()
                .withCacheConfiguration(PRODUCT, cacheConfiguration(objectMapper, item, ttl))
                .withCacheConfiguration(PRODUCTS, cacheConfiguration(objectMapper, page, ttl));
    }

    private static RedisCacheConfiguration cacheConfiguration(ObjectMapper objectMapper, JavaType type,
                                                              Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }

    /** Failure scenario 11: Redis down means slower reads from PostgreSQL, never a failed request. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
