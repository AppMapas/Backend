package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import com.seminario.legaladministrator.modules.users.repository.MaritalStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaritalStatusServiceTest {

    @Mock
    private MaritalStatusRepository maritalStatusRepository;

    @InjectMocks
    private MaritalStatusService maritalStatusService;

    @Test
    void shouldReturnAllMaritalStatuses() {
        MaritalStatusEntity status = MaritalStatusEntity.builder().id(1L).name("Soltero").description("Sin pareja").build();
        when(maritalStatusRepository.findAll()).thenReturn(List.of(status));

        List<MaritalStatusEntity> statuses = maritalStatusService.findAll();

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).getName()).isEqualTo("Soltero");
    }

    @Test
    void shouldReturnMaritalStatusById() {
        MaritalStatusEntity status = MaritalStatusEntity.builder().id(2L).name("Casado").description("Con pareja").build();
        when(maritalStatusRepository.findById(2L)).thenReturn(Optional.of(status));

        assertThat(maritalStatusService.findById(2L)).contains(status);
    }
}
