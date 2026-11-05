package com.caravelo.routesync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "caravelo.sync.on-startup=false")
class RouteSyncApplicationTests {

	@Test
	void contextLoads() {
	}

}
