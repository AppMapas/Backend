package com.seminario.legaladministrator.modules.locations.service;

import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.locations.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class CountryService {
    private final CountryRepository countryRepository;

    public List<CountryEntity> findAll() {
        return countryRepository.findAll();
    }

    public Optional<CountryEntity> findById(Long id) {
        return countryRepository.findById(id);
    }

    public Optional<CountryEntity> findByName(String name) {
        return countryRepository.findByName(name);
    }

    public CountryEntity saveCountry(CountryEntity country) {
        return countryRepository.save(country);
    }

    public void deleteCountry(Long id) {
        countryRepository.deleteById(id);
    }
}
