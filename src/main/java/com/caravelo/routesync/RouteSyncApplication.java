package com.caravelo.routesync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RouteSyncApplication {

	public static void main(String[] args) {
		SpringApplication.run(RouteSyncApplication.class, args);
	}

}
