package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.CreateRequest;
import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import com.seminario.legaladministrator.shared.InputRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CaseRequestGuard {
    private final JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager entityManager;

    public void lockRequirement(RequirementEntity requirement) {
        entityManager.refresh(requirement, LockModeType.PESSIMISTIC_READ);
    }

    public void lock(UUID requestId) {
        // El bloqueo pertenece a la transacción y se libera incluso si esta falla.
        long key = requestId.getMostSignificantBits() ^ requestId.getLeastSignificantBits();
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", (rs, row) -> Boolean.TRUE, key);
    }

    public String fingerprint(CreateRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.clientDpi());
        append(canonical, request.processTypeId());
        append(canonical, request.processTypeVersion());
        append(canonical, InputRules.text(request.generalDetails()));
        if (request.client() != null) {
            var c = request.client();
            append(canonical, c.getDpi());
            append(canonical, InputRules.text(c.getFirstName()));
            append(canonical, InputRules.text(c.getLastName()));
            append(canonical, InputRules.text(c.getEmail()));
            append(canonical, c.getPhone());
            append(canonical, c.getBirthDate());
            append(canonical, c.getMaritalStatusId());
            append(canonical, c.getNationalityId());
            append(canonical, InputRules.text(c.getOccupation()));
            append(canonical, InputRules.text(c.getExactAddress()));
            append(canonical, c.getMunicipalityId());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible.", ex);
        }
    }

    private void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        String text = value.toString();
        target.append(text.length()).append(':').append(text);
    }
}
