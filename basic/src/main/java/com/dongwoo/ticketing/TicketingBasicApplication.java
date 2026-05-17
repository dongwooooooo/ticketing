package com.dongwoo.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class TicketingBasicApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketingBasicApplication.class, args);
	}

}
