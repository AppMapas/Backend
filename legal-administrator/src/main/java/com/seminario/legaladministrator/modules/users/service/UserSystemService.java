package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.auth.TotpService;
import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.locations.service.CountryService;
import com.seminario.legaladministrator.modules.users.Exceptions.*;
import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import com.seminario.legaladministrator.modules.users.RoleEntity;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.dto.UserSystemRegisterRequestDto;
import com.seminario.legaladministrator.modules.users.dto.UserSystemResponseDto;
import com.seminario.legaladministrator.modules.users.mappers.UserSystemMapper;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Transactional
public class UserSystemService {
    private final UserSystemRepository userSystemRepository;
    private final MaritalStatusService maritalStatusService;
    private final CountryService countryService;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final UserSystemMapper userSystemMapper;

    public List<UserSystemResponseDto> findAll() {
        return userSystemRepository.findAll().stream()
                .map(userSystemMapper::toResponseDto)
                .collect(Collectors.toList());
    }

    public Optional<UserSystemResponseDto> findByDpi(String dpi) {
        return userSystemRepository.findById(dpi)
                .map(userSystemMapper::toResponseDto);
    }

    public Optional<UserSystemEntity> findByEmail(String email) {
        return userSystemRepository.findByEmail(email);
    }

    public UserSystemResponseDto createUser(UserSystemRegisterRequestDto dto) {
        if (userSystemRepository.existsById(dto.getDpi())) {
            throw new UserAlreadyExistsException("Ya existe un usuario registrado con el DPI: " + dto.getDpi());
        }

        var maritalStatus = maritalStatusService.findById(dto.getIdMaritalStatus())
                .orElseThrow(() -> new MaritalStatusNotFoundException("Estado civil no encontrado"));
        var nationality = countryService.findById(dto.getIdNationality())
                .orElseThrow(() -> new NationalityNotFoundException("Nacionalidad no encontrada"));
        var role = roleService.findById(dto.getIdRole())
                .orElseThrow(() -> new RoleNotFoundException("Rol no encontrado"));

        UserSystemEntity newUser = UserSystemEntity.builder()
                .dpi(dto.getDpi())
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .age(dto.getAge())
                .email(dto.getEmail())
                .passwordHash(passwordEncoder.encode(dto.getPassword()))
                .maritalStatus(maritalStatus)
                .nationality(nationality)
                .role(role)
                .createdAt(LocalDate.now())
                .build();

        return userSystemMapper.toResponseDto(userSystemRepository.save(newUser));
    }

    public UserSystemEntity updateProfile(String dpi, UserSystemRegisterRequestDto dto) {
        UserSystemEntity existingUser = userSystemRepository.findById(dpi)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado con DPI: " + dpi));

        MaritalStatusEntity maritalStatus = maritalStatusService.findById(dto.getIdMaritalStatus())
                .orElseThrow(() -> new MaritalStatusNotFoundException("Estado civil no encontrado con ID: " + dto.getIdMaritalStatus()));

        CountryEntity nationality = countryService.findById(dto.getIdNationality())
                .orElseThrow(() -> new NationalityNotFoundException("Nacionalidad no encontrada con ID: " + dto.getIdNationality()));

        RoleEntity role = roleService.findById(dto.getIdRole())
                .orElseThrow(() -> new RoleNotFoundException("Rol no encontrado con ID: " + dto.getIdRole()));

        existingUser.setFirstName(dto.getFirstName());
        existingUser.setLastName(dto.getLastName());
        existingUser.setAge(dto.getAge());
        existingUser.setEmail(dto.getEmail());
        existingUser.setMaritalStatus(maritalStatus);
        existingUser.setNationality(nationality);
        existingUser.setRole(role);

        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            existingUser.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        }

        return userSystemRepository.save(existingUser);
    }

    public void deleteUser(String dpi) {
        if (!userSystemRepository.existsById(dpi)) {
            throw new UserNotFoundException("No se puede eliminar. Usuario no encontrado con DPI: " + dpi);
        }
        userSystemRepository.deleteById(dpi);
    }

    public void updatePassword(String dpi, String newPassword) {
        UserSystemEntity existingUser = userSystemRepository.findById(dpi)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado con DPI: " + dpi));

        if (newPassword == null || newPassword.isBlank()) {
            throw new InvalidPasswordException("La nueva contraseña no puede estar vacía");
        }

        existingUser.setPasswordHash(passwordEncoder.encode(newPassword));
        userSystemRepository.save(existingUser);
    }

    public void updateTwoFactorStatus(String dpi, boolean enabled) {
        UserSystemEntity user = userSystemRepository.findById(dpi)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado con DPI: " + dpi));

        user.setTwoFactorEnabled(enabled);

        // Si habilita 2FA y no tiene un secreto (guardado en twoFactorCode) asignado, se le genera uno
        if (enabled && (user.getTwoFactorCode() == null || user.getTwoFactorCode().isBlank())) {
            user.setTwoFactorCode(totpService.generateSecret());
        }

        userSystemRepository.save(user);
    }
}