package com.caravelo.routesync;

import org.springframework.boot.SpringApplication;

public class TestRouteSyncApplication {

	public static void main(String[] args) {
		SpringApplication.from(RouteSyncApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
