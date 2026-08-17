package synvo;

import org.springframework.boot.SpringApplication;

public class TestSynvoApplication {

	public static void main(String[] args) {
		SpringApplication.from(SynvoApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}
}
