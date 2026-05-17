package com.dongwoo.ticketing;

import org.springframework.boot.SpringApplication;

public class TestTicketingBasicApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketingBasicApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
