package com.ecommerce.order.infrastructure.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachedProductPricesTest {

    @Mock
    private ProductServiceClient productServiceClient;

    @Test
    @DisplayName("A repeated price lookup is served from the cache, not from product-service")
    void shouldCallProductServiceOncePerProductWithinTheTtl() {
        CachedProductPrices prices = new CachedProductPrices(productServiceClient, 60);
        when(productServiceClient.currentPrice(1L)).thenReturn(new BigDecimal("24.99"));

        assertThat(prices.currentPrice(1L)).isEqualByComparingTo("24.99");
        assertThat(prices.currentPrice(1L)).isEqualByComparingTo("24.99");
        assertThat(prices.currentPrice(1L)).isEqualByComparingTo("24.99");

        verify(productServiceClient, times(1)).currentPrice(1L);
    }

    @Test
    @DisplayName("Each product is cached separately")
    void shouldCacheEachProductIndependently() {
        CachedProductPrices prices = new CachedProductPrices(productServiceClient, 60);
        when(productServiceClient.currentPrice(1L)).thenReturn(new BigDecimal("24.99"));
        when(productServiceClient.currentPrice(2L)).thenReturn(new BigDecimal("89.00"));

        assertThat(prices.currentPrice(1L)).isEqualByComparingTo("24.99");
        assertThat(prices.currentPrice(2L)).isEqualByComparingTo("89.00");
        assertThat(prices.currentPrice(1L)).isEqualByComparingTo("24.99");

        verify(productServiceClient, times(1)).currentPrice(1L);
        verify(productServiceClient, times(1)).currentPrice(2L);
    }

    @Test
    @DisplayName("A zero TTL always asks product-service, so the cache can be turned off")
    void shouldBypassTheCacheWhenTtlIsZero() {
        CachedProductPrices prices = new CachedProductPrices(productServiceClient, 0);
        when(productServiceClient.currentPrice(1L)).thenReturn(new BigDecimal("24.99"));

        prices.currentPrice(1L);
        prices.currentPrice(1L);

        verify(productServiceClient, times(2)).currentPrice(1L);
    }
}
