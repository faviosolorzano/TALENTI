package com.talenti;

import org.springframework.boot.SpringApplication;

public class TestTalentiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(TalentiBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
