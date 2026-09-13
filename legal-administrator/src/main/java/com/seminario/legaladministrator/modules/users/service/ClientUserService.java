package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.dto.ClientUserRequestDto;
import com.seminario.legaladministrator.modules.users.dto.ClientUserResponseDto;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ClientUserService {
    private final ClientUserRepository clientUserRepository;

    @Transactional
    public ClientUserResponseDto createClient(ClientUserRequestDto request) {
        if (clientUserRepository.existsById(request.getDpi())) {
            throw new IllegalArgumentException("Ya existe un cliente registrado con el DPI: " + request.getDpi());
        }

        ClientUserEntity entity = ClientUserEntity.builder()
                .dpi(request.getDpi())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .createdAt(LocalDate.now())
                .build();

        ClientUserEntity saved = clientUserRepository.save(entity);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ClientUserResponseDto> getAllClients() {
        return clientUserRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ClientUserResponseDto getClientByDpi(String dpi) {
        ClientUserEntity entity = clientUserRepository.findById(dpi)
                .orElseThrow(() -> new EntityNotFoundException("Cliente no encontrado con DPI: " + dpi));
        return mapToResponse(entity);
    }

    @Transactional
    public ClientUserResponseDto updateClient(String dpi, ClientUserRequestDto request) {
        ClientUserEntity entity = clientUserRepository.findById(dpi)
                .orElseThrow(() -> new EntityNotFoundException("Cliente no encontrado con DPI: " + dpi));

        entity.setFirstName(request.getFirstName());
        entity.setLastName(request.getLastName());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());

        ClientUserEntity updated = clientUserRepository.save(entity);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteClient(String dpi) {
        if (!clientUserRepository.existsById(dpi)) {
            throw new EntityNotFoundException("Cliente no encontrado con DPI: " + dpi);
        }
        clientUserRepository.deleteById(dpi);
    }

    private ClientUserResponseDto mapToResponse(ClientUserEntity entity) {
        ClientUserResponseDto response = new ClientUserResponseDto();
        response.setDpi(entity.getDpi());
        response.setFirstName(entity.getFirstName());
        response.setLastName(entity.getLastName());
        response.setEmail(entity.getEmail());
        response.setPhone(entity.getPhone());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
