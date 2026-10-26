package com.dashcart;

import org.springframework.boot.SpringApplication;

public class TestDashcartApplication {

	public static void main(String[] args) {
		SpringApplication.from(DashcartApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
