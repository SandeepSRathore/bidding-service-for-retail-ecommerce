package com.retail.bidding.auction;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
class HttpClientConfig {

    /** Short timeouts: these calls sit on the admin's and the vendor's request path. */
    @Bean
    RestClientCustomizer timeouts() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder -> builder.requestFactory(factory);
    }
}
