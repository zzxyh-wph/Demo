package com.example.demo2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
public class Demo2Application {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Demo2Application.class);
        if (args.length > 0 && isCliCommand(args[0])) {
            application.setWebApplicationType(WebApplicationType.NONE);
            System.exit(SpringApplication.exit(application.run(args)));
            return;
        }

        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.run(args);
    }

    private static boolean isCliCommand(String arg0) {
        return "help".equalsIgnoreCase(arg0)
                || "generate".equalsIgnoreCase(arg0)
                || "diff".equalsIgnoreCase(arg0)
                || "apply".equalsIgnoreCase(arg0);
    }
}
