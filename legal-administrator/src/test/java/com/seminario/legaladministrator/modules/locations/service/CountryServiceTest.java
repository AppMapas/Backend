package com.seminario.legaladministrator.modules.locations.service;

import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.locations.repository.CountryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryServiceTest {

    @Mock
    private CountryRepository countryRepository;

    @InjectMocks
    private CountryService countryService;

    @Test
    void shouldReturnCountriesAndSearchByName() {
        CountryEntity country = CountryEntity.builder().id(1L).name("Guatemala").isoCode("GT").build();
        when(countryRepository.findAll()).thenReturn(List.of(country));
        when(countryRepository.findByName("Guatemala")).thenReturn(Optional.of(country));

        assertThat(countryService.findAll()).hasSize(1);
        assertThat(countryService.findByName("Guatemala")).contains(country);
    }

    @Test
    void shouldSaveAndDeleteCountry() {
        CountryEntity country = CountryEntity.builder().id(2L).name("El Salvador").isoCode("SV").build();
        when(countryRepository.save(country)).thenReturn(country);

        assertThat(countryService.saveCountry(country)).isEqualTo(country);
        countryService.deleteCountry(2L);

        verify(countryRepository).save(country);
        verify(countryRepository).deleteById(2L);
    }
}
