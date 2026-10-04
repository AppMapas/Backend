package com.seminario.legaladministrator.modules.users.controller;

import com.seminario.legaladministrator.modules.users.dto.ClientUserRequestDto;
import com.seminario.legaladministrator.modules.users.dto.ClientUserResponseDto;
import com.seminario.legaladministrator.modules.users.dto.ClientUserUpdateDto;
import com.seminario.legaladministrator.shared.PageResponse;
import com.seminario.legaladministrator.shared.VersionRequest;
import com.seminario.legaladministrator.modules.users.service.ClientUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
@RestController
public class ClientUserController {
    private final ClientUserService clientUserService;

    @PostMapping
    public ResponseEntity<ClientUserResponseDto> createClient(@Valid @RequestBody ClientUserRequestDto request) {
        ClientUserResponseDto response = clientUserService.createClient(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<ClientUserResponseDto>> getAllClients() {
        List<ClientUserResponseDto> clients = clientUserService.getAllClients();
        return ResponseEntity.ok(clients);
    }

    @GetMapping("/{dpi}")
    public ResponseEntity<ClientUserResponseDto> getClientByDpi(@PathVariable String dpi) {
        ClientUserResponseDto response = clientUserService.getClientByDpi(dpi);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{dpi}")
    public ResponseEntity<ClientUserResponseDto> updateClient(
            @PathVariable String dpi,
            @Valid @RequestBody ClientUserUpdateDto request) {
        ClientUserResponseDto response = clientUserService.updateClient(dpi, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{dpi}")
    public ResponseEntity<Void> deleteClient(@PathVariable String dpi, @RequestParam Long version) {
        clientUserService.deactivateClient(dpi, version);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{dpi}/deactivate")
    public ClientUserResponseDto deactivate(@PathVariable String dpi, @Valid @RequestBody VersionRequest request) {
        return clientUserService.deactivateClient(dpi, request.version());
    }

    @GetMapping("/search")
    public PageResponse<ClientUserResponseDto> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return clientUserService.search(q, active, page, size);
    }
}
