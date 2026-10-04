package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.dto.*;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import com.seminario.legaladministrator.modules.users.repository.MaritalStatusRepository;
import com.seminario.legaladministrator.modules.locations.repository.CountryRepository;
import com.seminario.legaladministrator.modules.locations.repository.MunicipalityRepository;
import com.seminario.legaladministrator.shared.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class ClientUserService {
    private final ClientUserRepository clientUserRepository;
    private final MaritalStatusRepository maritalStatusRepository;
    private final CountryRepository countryRepository;
    private final MunicipalityRepository municipalityRepository;
    private final Validator validator;

    @Transactional
    public ClientUserResponseDto createClient(ClientUserRequestDto request) {
        return mapToResponse(createEntity(request));
    }

    @Transactional
    public ClientUserEntity createEntity(ClientUserRequestDto request) {
        validate(request);
        InputRules.dpi(request.getDpi());
        if (clientUserRepository.existsById(request.getDpi())) {
            throw new OperationException(HttpStatus.CONFLICT,
                    "Ya existe un cliente con ese DPI. Selecciona el registro existente.");
        }
        ClientUserEntity entity = new ClientUserEntity();
        entity.setDpi(request.getDpi());
        entity.setActive(true);
        entity.setCreatedAt(LocalDate.now());
        applyPersonalData(entity, request);
        return clientUserRepository.saveAndFlush(entity);
    }

    @Transactional(readOnly = true)
    public List<ClientUserResponseDto> getAllClients() {
        return clientUserRepository.findAll().stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClientUserResponseDto getClientByDpi(String dpi) {
        return mapToResponse(findClient(dpi));
    }

    @Transactional
    public ClientUserResponseDto updateClient(String dpi, ClientUserUpdateDto request) {
        validate(request);
        ClientUserEntity entity = findClient(dpi);
        checkVersion(entity, request.getVersion());
        if (!entity.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El cliente está inactivo.");
        }
        applyPersonalData(entity, request);
        return mapToResponse(clientUserRepository.saveAndFlush(entity));
    }

    @Transactional
    public ClientUserResponseDto deactivateClient(String dpi, Long version) {
        InputRules.dpi(dpi);
        ClientUserEntity entity = clientUserRepository.findForUpdate(dpi)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Cliente no encontrado."));
        checkVersion(entity, version);
        entity.setActive(false);
        return mapToResponse(clientUserRepository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public PageResponse<ClientUserResponseDto> search(String query, Boolean active, int page, int size) {
        var pagination = InputRules.page(page, size, Sort.by("lastName", "firstName", "dpi"));
        String term = InputRules.search(query);
        Specification<ClientUserEntity> criteria = (root, cq, cb) -> {
            var match = cb.conjunction();
            if (!term.isEmpty()) {
                for (String token : term.split("\\s+")) {
                    match = cb.and(match, cb.or(
                            cb.greaterThan(cb.locate(cb.lower(root.get("firstName")), token), 0),
                            cb.greaterThan(cb.locate(cb.lower(root.get("lastName")), token), 0),
                            cb.greaterThan(cb.locate(root.get("dpi"), token), 0)));
                }
            }
            if (active != null) {
                match = cb.and(match, cb.equal(root.get("active"), active));
            }
            return match;
        };
        return PageResponse.from(clientUserRepository.findAll(criteria, pagination).map(this::mapToResponse));
    }

    private ClientUserEntity findClient(String dpi) {
        InputRules.dpi(dpi);
        return clientUserRepository.findById(dpi)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Cliente no encontrado."));
    }

    private void checkVersion(ClientUserEntity entity, Long version) {
        if (version == null) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La versión es obligatoria.");
        }
        if (!Objects.equals(entity.getVersion(), version)) {
            throw new OperationException(HttpStatus.CONFLICT, "El cliente cambió. Recarga sus datos antes de editar.");
        }
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Los datos del cliente están incompletos o no son válidos.");
        }
    }

    private void applyPersonalData(ClientUserEntity entity, ClientPersonalDataDto request) {
        entity.setFirstName(InputRules.required(request.getFirstName(), 100, "El nombre"));
        entity.setLastName(InputRules.required(request.getLastName(), 100, "El apellido"));
        entity.setEmail(InputRules.required(request.getEmail(), 100, "El correo").toLowerCase(Locale.ROOT));
        entity.setPhone(InputRules.required(request.getPhone(), 13, "El teléfono"));
        entity.setExactAddress(InputRules.required(request.getExactAddress(), 255, "La dirección"));
        entity.setBirthDate(request.getBirthDate());
        entity.setOccupation(InputRules.text(request.getOccupation()));
        entity.setMaritalStatus(maritalStatusRepository.findById(request.getMaritalStatusId())
                .orElseThrow(() -> new OperationException(HttpStatus.BAD_REQUEST, "Estado civil no válido.")));
        entity.setNationality(countryRepository.findById(request.getNationalityId())
                .orElseThrow(() -> new OperationException(HttpStatus.BAD_REQUEST, "Nacionalidad no válida.")));
        entity.setMunicipality(null);
        if (request.getMunicipalityId() != null) {
            entity.setMunicipality(municipalityRepository.findById(request.getMunicipalityId())
                    .orElseThrow(() -> new OperationException(HttpStatus.BAD_REQUEST, "Municipio no válido.")));
        }
    }

    private ClientUserResponseDto mapToResponse(ClientUserEntity entity) {
        ClientUserResponseDto response = new ClientUserResponseDto();
        response.setDpi(entity.getDpi());
        response.setFirstName(entity.getFirstName());
        response.setLastName(entity.getLastName());
        response.setEmail(entity.getEmail());
        response.setPhone(entity.getPhone());
        response.setCreatedAt(entity.getCreatedAt());
        response.setBirthDate(entity.getBirthDate());
        if (entity.getMaritalStatus() != null) {
            response.setMaritalStatusId(entity.getMaritalStatus().getId());
        }
        if (entity.getNationality() != null) {
            response.setNationalityId(entity.getNationality().getId());
        }
        if (entity.getMunicipality() != null) {
            response.setMunicipalityId(entity.getMunicipality().getId());
        }
        response.setOccupation(entity.getOccupation());
        response.setExactAddress(entity.getExactAddress());
        response.setActive(entity.isActive());
        response.setVersion(entity.getVersion());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
