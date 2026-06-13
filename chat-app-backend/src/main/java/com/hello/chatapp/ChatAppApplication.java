package com.hello.chatapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.hello.chatapp.config.EarlyPropertyPrinter;

@SpringBootApplication
public class ChatAppApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(ChatAppApplication.class);

		// Register the initializer here to run BEFORE the DB loads
		app.addInitializers(new EarlyPropertyPrinter());

		app.run(args);
	}

}
