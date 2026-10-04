package com.ecommerce.eurekaserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerApplicationTests {

    @Test
    @DisplayName("Should load Spring Cloud Netflix Eureka Server context successfully")
    void contextLoads() {
    }
}
