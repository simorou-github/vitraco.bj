package bj.vitraco.api;

import org.springframework.boot.SpringApplication;

public class TestVitracosarlApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(VitracosarlApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
