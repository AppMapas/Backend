package com.seminario.legaladministrator.modules.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    /** Cuenta todos los expedientes vigentes, independientemente de la paginación del listado. */
    public long activeCases() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM legal_process WHERE active = true",
            Long.class
        );
        return java.util.Objects.requireNonNull(count);
    }
}
