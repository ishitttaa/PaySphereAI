package com.paysphere.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI Configuration
 *
 * SpringDoc auto-generates API documentation from your @RestController classes.
 * Accessible at: http://localhost:8080/swagger-ui.html
 *
 * This is extremely useful during development and for technical interviews —
 * you can demonstrate every endpoint interactively without Postman.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI paySpereOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PaySphere API")
                        .description("""
                                PaySphere — Secure Payment Integration, Orchestration,
                                Reconciliation, and Ledger Management Platform.
                                
                                This API powers the PaySphere dashboard and integrates
                                with Razorpay for payment processing.
                                
                                ⚠️ All financial operations are verified server-side.
                                Never trust client-provided amounts or payment status.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("PaySphere Team")
                                .email("admin@paysphere.local")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server")
                ));
    }
}
