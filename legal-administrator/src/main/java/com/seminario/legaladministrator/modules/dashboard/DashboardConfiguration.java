package com.seminario.legaladministrator.modules.dashboard;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashboardConfiguration {

    /** Un reloj inyectable permite probar límites de día sin depender de la hora de ejecución. */
    @Bean("dashboardClock")
    public Clock dashboardClock() {
        return Clock.systemUTC();
    }
}
