package app.zylos.cart.adapter.out.relay;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the fixed-delay poll that drives the outbox relay. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RelaySchedulingConfig {}
