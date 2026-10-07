package com.seminario.legaladministrator.hu05;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.*;
import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.*;
import com.seminario.legaladministrator.modules.processes.repository.*;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.processes.service.LegalProcessService;
import com.seminario.legaladministrator.modules.users.dto.ClientUserUpdateDto;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import com.seminario.legaladministrator.modules.users.service.ClientUserService;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Requiere PostgreSQL desechable, sin servidor HTTP ni contenedores.
 * Nunca utiliza la conexión de .env.
 */
@EnabledIfSystemProperty(named = "hu05.integration", matches = "true")
@DataJpaTest(showSql = false, properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.flyway.baseline-on-migrate=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClientUserService.class, LegalProcessService.class, OfficeAccess.class,
        CaseRequestGuard.class, Hu05PersistenceTest.Configuration.class})
class Hu05PersistenceTest {
    @Autowired ClientUserService clients;
    @Autowired LegalProcessService cases;
    @Autowired ClientUserRepository clientRepository;
    @Autowired ProcessTypeRepository templates;
    @Autowired RequirementRepository requirementRepository;
    @Autowired ProcessTypeRequirementRepository links;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private ProcessTypeEntity template;
    private RequirementEntity requirement;
    private String dpi;

    @TestConfiguration
    @EnableMethodSecurity
    static class Configuration {
        @Bean(destroyMethod = "close")
        ValidatorFactory validatorFactory() {
            return Validation.buildDefaultValidatorFactory();
        }
        @Bean
        Validator validator(ValidatorFactory factory) {
            return factory.getValidator();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("hu05.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hu05_test")) {
            throw new IllegalArgumentException("Usa una base local desechable llamada hu05_test con puerto explícito.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("hu05.test.user", "krm"));
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeEach
    void prepare() {
        authenticate("abogada@system.com", "Abogada");
        dpi = "8" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            requirement = new RequirementEntity();
            requirement.setName("DPI HU05 " + UUID.randomUUID());
            requirement.setDescription("Copia legible");
            requirement = requirementRepository.saveAndFlush(requirement);
            template = new ProcessTypeEntity();
            template.setName("Memorial HU05 " + UUID.randomUUID());
            template.setStatus(ProcessTypeStatus.PUBLISHED);
            template = templates.saveAndFlush(template);
            var link = new ProcessTypeRequirementEntity();
            link.setProcessType(template);
            link.setRequirement(requirement);
            link.setRequired(true);
            link.setRequiresDocument(true);
            link.setDisplayOrder(1);
            link.setInstructions("Presentar copia");
            links.saveAndFlush(link);
        });
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsClientCaseAndOrderedSnapshotsInOneTransaction() {
        var created = cases.create(newRequest());
        entityManager.clear();
        var detail = cases.get(created.detail().caseData().id());
        assertThat(created.replayed()).isFalse();
        assertThat(detail.caseData().clientDpi()).isEqualTo(dpi);
        assertThat(detail.caseData().caseCode()).startsWith("EXP-");
        assertThat(detail.caseData().assignedUserDpi()).isEqualTo("3002234560901");
        assertThat(detail.caseData().currentStatus()).isEqualTo("OPEN");
        assertThat(detail.requirements()).hasSize(1);
        assertThat(detail.requirements().get(0).name()).isEqualTo(requirement.getName());
        assertThat(detail.requirements().get(0).status()).isEqualTo("PENDING");
        assertThat(detail.requirements().get(0).requiresDocument()).isTrue();
        assertThat(clients.getClientByDpi(dpi).getNationalityId()).isEqualTo(1L);
    }

    @Test
    void existingClientSupportsMultipleCasesWithoutDuplication() {
        clients.createClient(Hu05Fixtures.client(dpi));
        var first = cases.create(existingRequest(UUID.randomUUID()));
        var second = cases.create(existingRequest(UUID.randomUUID()));
        assertThat(first.detail().caseData().id()).isNotEqualTo(second.detail().caseData().id());
        assertThat(cases.search("", dpi, null, null, true, 0, 20).totalElements()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from client_user where dpi = ?", Long.class, dpi)).isEqualTo(1);
    }

    @Test
    void sameKeyReplaysButChangedPayloadConflicts() {
        var request = newRequest();
        var first = cases.create(request);
        var retry = cases.create(request);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.detail().caseData().id()).isEqualTo(first.detail().caseData().id());
        var changed = new CreateRequest(request.requestId(), null, request.client(),
                template.getId(), template.getVersion(), "Otra descripción", null);
        assertThatThrownBy(() -> cases.create(changed)).isInstanceOf(OperationException.class);
        assertThat(cases.search("", dpi, null, null, true, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void requestKeyCannotBeReplayedByAnotherOperator() {
        var request = newRequest();
        cases.create(request);
        authenticate("admin@system.com", "Administrador");
        assertThatThrownBy(() -> cases.create(request))
                .isInstanceOf(OperationException.class).hasMessageContaining("clave");
    }

    @Test
    void rejectsDraftStaleTemplateAndInactiveRequirement() {
        var request = newRequest();
        template.setStatus(ProcessTypeStatus.DRAFT);
        templates.saveAndFlush(template);
        assertThatThrownBy(() -> cases.create(request)).hasMessageContaining("publicado");
        template.setStatus(ProcessTypeStatus.PUBLISHED);
        templates.saveAndFlush(template);
        assertThatThrownBy(() -> cases.create(request)).hasMessageContaining("cambió");
        requirement.setActive(false);
        requirementRepository.saveAndFlush(requirement);
        assertThatThrownBy(() -> cases.create(newRequest())).hasMessageContaining("inactivos");
        assertThat(clientRepository.existsById(dpi)).isFalse();
    }

    @Test
    void rejectsInactiveAndIncompleteHistoricalClients() {
        clients.createClient(Hu05Fixtures.client(dpi));
        clients.deactivateClient(dpi, 0L);
        assertThatThrownBy(() -> cases.create(existingRequest(UUID.randomUUID()))).hasMessageContaining("inactivo");
        var legacyDpi = jdbc.queryForObject(
                "select dpi from client_user where id_nationality is null limit 1", String.class);
        var legacy = new CreateRequest(UUID.randomUUID(), legacyDpi, null,
                template.getId(), template.getVersion(), null, null);
        assertThatThrownBy(() -> cases.create(legacy)).hasMessageContaining("Completa");
    }

    @Test
    void searchPaginatesByNameDpiAndCaseCodeAndTreatsWildcardsLiterally() {
        var first = cases.create(newRequest()).detail().caseData();
        assertThat(clients.search("ANA PÉREZ", true, 0, 1).content()).hasSize(1);
        assertThat(cases.search(first.caseCode(), dpi, template.getId(), "OPEN", true, 0, 1).content()).hasSize(1);
        assertThat(cases.search(dpi, null, null, null, true, 0, 1).totalElements()).isEqualTo(1);
        assertThat(cases.search("%", null, null, null, true, 0, 1).content()).isEmpty();
    }

    @Test
    void editsWithVersionAndDoesNotChangeClientOrTemplate() {
        var first = cases.create(newRequest()).detail().caseData();
        var edited = cases.update(first.id(), new UpdateRequest(first.version(), "Observación nueva")).caseData();
        assertThat(edited.version()).isGreaterThan(first.version());
        assertThat(edited.clientDpi()).isEqualTo(first.clientDpi());
        assertThat(edited.processTypeId()).isEqualTo(first.processTypeId());
        assertThatThrownBy(() -> cases.update(first.id(), new UpdateRequest(first.version(), "Edición obsoleta")))
                .hasMessageContaining("cambió");

        var update = new ClientUserUpdateDto();
        update.setVersion(0L);
        update.setFirstName("Ana");
        update.setLastName("Pérez");
        update.setEmail("ana@example.test");
        update.setPhone("55551234");
        update.setNationalityId(1L);
        update.setMaritalStatusId(1L);
        update.setExactAddress("Zona 2");
        assertThat(clients.updateClient(dpi, update).getVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> clients.updateClient(dpi, update)).hasMessageContaining("cambió");
    }

    @Test
    void historicalSnapshotsSurviveChangesToCatalogs() {
        var created = cases.create(newRequest()).detail();
        requirement.setName("Nombre distinto " + UUID.randomUUID());
        requirementRepository.saveAndFlush(requirement);
        template.setName("Plantilla distinta " + UUID.randomUUID());
        templates.saveAndFlush(template);
        entityManager.clear();
        var recovered = cases.get(created.caseData().id());
        assertThat(recovered.requirements()).isEqualTo(created.requirements());
        assertThat(recovered.caseData().processTypeName()).isEqualTo(created.caseData().processTypeName());
    }

    @Test
    void revokedDatabaseRoleBlocksEvenWhenTokenStillSaysAbogada() {
        jdbc.update("update user_system set id_role = (select id from role where name = 'Secretaria') where email = ?",
                "abogada@system.com");
        entityManager.clear();
        assertThatThrownBy(() -> clients.getAllClients())
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failureSavingSnapshotRollsBackBothNewClientAndCase() {
        jdbc.update("update requirement set name = 'Rollback sentinel' where id = ?", requirement.getId());
        jdbc.execute("alter table legal_process_requirement add constraint hu05_snapshot_failure "
                + "check(name_snapshot <> 'Rollback sentinel')");
        try {
            assertThatThrownBy(() -> cases.create(newRequest())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(clientRepository.existsById(dpi)).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from legal_process where dpi_client = ?", Long.class, dpi))
                    .isZero();
        } finally {
            jdbc.execute("alter table legal_process_requirement drop constraint hu05_snapshot_failure");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRetriesCreateExactlyOneCase() throws Exception {
        var request = newRequest();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Creation> action = () -> {
            authenticate("abogada@system.com", "Abogada");
            try {
                start.await(5, TimeUnit.SECONDS);
                return cases.create(request);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Creation::replayed).count()).isEqualTo(1);
            assertThat(results).extracting(result -> result.detail().caseData().id()).containsOnly(
                    results.get(0).detail().caseData().id());
            assertThat(jdbc.queryForObject("select count(*) from legal_process where request_id = ?",
                    Long.class, request.requestId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private CreateRequest newRequest() {
        return new CreateRequest(UUID.randomUUID(), null, Hu05Fixtures.client(dpi),
                template.getId(), template.getVersion(), "Solicitud inicial", null);
    }

    private CreateRequest existingRequest(UUID id) {
        return new CreateRequest(id, dpi, null, template.getId(), template.getVersion(), null, null);
    }

    private static void authenticate(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority(role))));
    }
}
