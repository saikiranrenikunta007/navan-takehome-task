package com.navan.task.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI receiptApi() {
        return new OpenAPI().info(new Info()
                .title("Receipt Auto-Itemize API")
                .version("1.0.0")
                .description("Upload receipts, extract taxes and line items, and auto-itemize into one transaction per receipt."));
    }
}
