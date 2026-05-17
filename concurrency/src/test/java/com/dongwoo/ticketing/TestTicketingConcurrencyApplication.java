package com.dongwoo.ticketing;

import org.springframework.boot.SpringApplication;

public class TestTicketingConcurrencyApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketingConcurrencyApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
