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
import java.util.List;
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

    public boolean identifiesExpense(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from expense_record where request_id = ?)", Boolean.class, requestId));
    }

    public String fingerprint(CreateRequest request) {
        var values = new java.util.ArrayList<Object>();
        values.add(request.clientDpi());
        values.add(request.processTypeId());
        values.add(request.processTypeVersion());
        values.add(InputRules.text(request.generalDetails()));
        if (request.client() != null) {
            var c = request.client();
            values.add(c.getDpi());
            values.add(InputRules.text(c.getFirstName()));
            values.add(InputRules.text(c.getLastName()));
            values.add(InputRules.text(c.getEmail()));
            values.add(c.getPhone());
            values.add(c.getBirthDate());
            values.add(c.getMaritalStatusId());
            values.add(c.getNationalityId());
            values.add(InputRules.text(c.getOccupation()));
            values.add(InputRules.text(c.getExactAddress()));
            values.add(c.getMunicipalityId());
        }
        return fingerprint(values);
    }

    /**
     * Huella de una carga arbitraria de valores ya normalizados. Cada valor se
     * antepone con su longitud, para que "1" + "23" no sea ambiguo con "12" + "3".
     */
    public String fingerprint(List<Object> values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            append(canonical, value);
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
