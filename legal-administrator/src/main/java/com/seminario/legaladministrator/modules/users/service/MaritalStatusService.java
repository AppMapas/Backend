package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import com.seminario.legaladministrator.modules.users.repository.MaritalStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class MaritalStatusService {
    private final MaritalStatusRepository maritalStatusRepository;

    public List<MaritalStatusEntity> findAll() {
        return maritalStatusRepository.findAll();
    }

    public Optional<MaritalStatusEntity> findById(Long id) {
        return maritalStatusRepository.findById(id);
    }
}
