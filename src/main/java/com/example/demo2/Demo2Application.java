package com.example.demo2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
public class Demo2Application {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Demo2Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        System.exit(SpringApplication.exit(application.run(args)));
    }

}
