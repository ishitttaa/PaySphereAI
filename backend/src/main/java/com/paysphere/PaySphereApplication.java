package com.paysphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * PaySphere Application Entry Point
 *
 * @SpringBootApplication is a convenience annotation that combines:
 *   - @Configuration        → this class can define Spring beans
 *   - @EnableAutoConfiguration → Spring Boot auto-wires beans based on classpath
 *   - @ComponentScan        → scans com.paysphere.** for @Component, @Service, etc.
 *
 * @EnableAsync enables @Async annotation support — used by AuditService to write
 * audit logs on a background thread without blocking the main request thread.
 *
 * Everything starts here. Spring Boot reads application.yml, runs Flyway migrations,
 * starts embedded Tomcat, and wires all the application beans.
 */
@SpringBootApplication
@EnableAsync
public class PaySphereApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaySphereApplication.class, args);
    }
}
