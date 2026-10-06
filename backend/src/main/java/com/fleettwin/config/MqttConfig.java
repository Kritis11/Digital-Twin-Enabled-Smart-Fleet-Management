package com.fleettwin.config;

import com.fleettwin.telemetry.TelemetryIngestService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.MqttHeaders;

@Configuration
public class MqttConfig {

    @Bean
    MqttPahoClientFactory mqttClientFactory(
            @Value("${fleet.mqtt.url}") String url,
            @Value("${fleet.mqtt.username}") String username,
            @Value("${fleet.mqtt.password}") String password) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] {url});
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setAutomaticReconnect(true);
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    /** For commands to vehicles (fleet/{id}/maintenance). Its own client id: a broker allows one connection per id. */
    @Bean
    MqttPahoMessageHandler mqttOutbound(
            MqttPahoClientFactory mqttClientFactory,
            @Value("${fleet.mqtt.url}") String url,
            @Value("${fleet.mqtt.client-id}") String clientId) {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(url, clientId + "-pub", mqttClientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(1);
        return handler;
    }

    @Bean
    IntegrationFlow telemetryInboundFlow(
            MqttPahoClientFactory mqttClientFactory,
            TelemetryIngestService ingestService,
            @Value("${fleet.mqtt.url}") String url,
            @Value("${fleet.mqtt.client-id}") String clientId,
            @Value("${fleet.mqtt.topic}") String topic) {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(url, clientId, mqttClientFactory, topic);
        adapter.setQos(1);
        return IntegrationFlow.from(adapter)
                .handle(String.class, (payload, headers) -> {
                    ingestService.ingest(headers.get(MqttHeaders.RECEIVED_TOPIC, String.class), payload);
                    return null;
                })
                .get();
    }
}
