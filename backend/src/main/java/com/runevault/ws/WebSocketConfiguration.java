package com.runevault.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@Profile("auth")
@EnableWebSocket
@EnableScheduling
public class WebSocketConfiguration implements WebSocketConfigurer {
 private final TransferSocketHandler handler;
 private final TicketHandshakeInterceptor tickets;
 public WebSocketConfiguration(TransferSocketHandler handler, TicketHandshakeInterceptor tickets) {
  this.handler = handler; this.tickets = tickets;
 }
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
  registry.addHandler(handler, "/ws/transfers").addInterceptors(tickets)
   .setAllowedOrigins(tickets.allowedOrigins());
 }
}
