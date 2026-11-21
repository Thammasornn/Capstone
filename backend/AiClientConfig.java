package com.faculty.attendance.face;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiClientConfig {

    @Bean
    RestClient aiRestClient(@Value("${ai-service.base-url}") String baseUrl,
                            @Value("${ai-service.connect-timeout-ms}") int connectMs,
                            @Value("${ai-service.read-timeout-ms}") int readMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
