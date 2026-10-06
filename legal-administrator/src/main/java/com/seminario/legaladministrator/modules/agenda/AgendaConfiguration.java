package com.seminario.legaladministrator.modules.agenda;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita el trabajador periódico que publica los cambios pendientes de Agenda en Google. */
@Configuration
@EnableScheduling
public class AgendaConfiguration {}
