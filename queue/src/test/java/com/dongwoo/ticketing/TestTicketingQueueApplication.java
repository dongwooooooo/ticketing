package com.dongwoo.ticketing;

import org.springframework.boot.SpringApplication;

public class TestTicketingQueueApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketingQueueApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
