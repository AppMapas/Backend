package com.seminario.legaladministrator.modules.users.controller;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.dto.UserSystemRegisterRequestDto;
import com.seminario.legaladministrator.modules.users.service.UserSystemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
public class UserSystemController {
    private final UserSystemService userSystemService;

    @GetMapping
    public ResponseEntity<List<UserSystemEntity>> getAllUsers() {
        return ResponseEntity.ok(userSystemService.findAll());
    }

    @GetMapping("/{dpi}")
    public ResponseEntity<UserSystemEntity> getUserByDpi(@PathVariable String dpi) {
        return userSystemService.findByDpi(dpi)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/register")
    public ResponseEntity<UserSystemEntity> createUser(@Valid @RequestBody UserSystemRegisterRequestDto dto) {
        UserSystemEntity savedUser = userSystemService.createUser(dto);
        return ResponseEntity.ok(savedUser);
    }

    @PutMapping("/profile/{dpi}")
    public ResponseEntity<UserSystemEntity> updateProfile(
            @PathVariable String dpi,
            @Valid @RequestBody UserSystemRegisterRequestDto dto) {
        UserSystemEntity updatedUser = userSystemService.updateProfile(dpi, dto);
        return ResponseEntity.ok(updatedUser);
    }

    @PatchMapping("/password/{dpi}")
    public ResponseEntity<Void> updatePassword(
            @PathVariable String dpi,
            @RequestBody Map<String, String> requestBody) {
        String newPassword = requestBody.get("password");
        userSystemService.updatePassword(dpi, newPassword);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{dpi}")
    public ResponseEntity<Void> deleteUser(@PathVariable String dpi) {
        userSystemService.deleteUser(dpi);
        return ResponseEntity.noContent().build();
    }
}