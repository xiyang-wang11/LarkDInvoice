package com.larkdinvoice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.client.KingdeeInvoiceClient;
import com.larkdinvoice.client.KingdeeInvoiceClientImpl;
import com.larkdinvoice.service.KingdeeAuthService;
import com.larkdinvoice.service.LarkNotifyService;
import com.larkdinvoice.service.LarkNotifyServiceImpl;
import com.larkdinvoice.service.impl.KingdeeAuthServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    public KingdeeAuthService kingdeeAuthService(AppConfig.KingdeeConfig kingdeeConfig,
                                                  ObjectMapper objectMapper) {
        return new KingdeeAuthServiceImpl(kingdeeConfig, objectMapper);
    }

    @Bean
    public KingdeeInvoiceClient kingdeeInvoiceClient(AppConfig.KingdeeConfig kingdeeConfig,
                                                      KingdeeAuthService kingdeeAuthService,
                                                      ObjectMapper objectMapper) {
        return new KingdeeInvoiceClientImpl(kingdeeConfig, kingdeeAuthService, objectMapper);
    }

    @Bean
    public LarkNotifyService larkNotifyService(AppConfig appConfig, ObjectMapper objectMapper) {
        return new LarkNotifyServiceImpl(appConfig, objectMapper);
    }
}
