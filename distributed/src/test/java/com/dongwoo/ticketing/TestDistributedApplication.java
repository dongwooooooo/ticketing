package com.dongwoo.ticketing;

import org.springframework.boot.SpringApplication;

public class TestDistributedApplication {

    public static void main(String[] args) {
        SpringApplication.from(DistributedApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
