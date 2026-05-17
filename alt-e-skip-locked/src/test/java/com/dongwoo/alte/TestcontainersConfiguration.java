package com.dongwoo.alte;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // max_connections 상향 — 시나리오 1(100 threads)과 시나리오 2(1000 threads, pool=120) 모두 수용.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withCommand("postgres", "-c", "max_connections=300");
    }
}
