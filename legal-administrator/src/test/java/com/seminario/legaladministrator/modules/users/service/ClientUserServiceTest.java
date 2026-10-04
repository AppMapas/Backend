package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.hu05.Hu05Fixtures;
import com.seminario.legaladministrator.modules.locations.CountryEntity;
import com.seminario.legaladministrator.modules.locations.repository.CountryRepository;
import com.seminario.legaladministrator.modules.locations.repository.MunicipalityRepository;
import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.MaritalStatusEntity;
import com.seminario.legaladministrator.modules.users.repository.*;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientUserServiceTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    @Mock ClientUserRepository clients;
    @Mock CountryRepository countries;
    @Mock MaritalStatusRepository maritalStatuses;
    @Mock MunicipalityRepository municipalities;
    private ClientUserService service;

    @BeforeEach
    void setUp() {
        service = new ClientUserService(clients, maritalStatuses, countries, municipalities, FACTORY.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @Test
    void rejectsDuplicateWithoutOverwritingExistingClient() {
        when(clients.existsById("1234567890123")).thenReturn(true);
        assertThatThrownBy(() -> service.createClient(Hu05Fixtures.client("1234567890123")))
                .isInstanceOfSatisfying(OperationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(clients, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidPersonalDataBeforeDatabaseAccess() {
        var request = Hu05Fixtures.client("1234567890123");
        request.setEmail("invalid");
        assertThatThrownBy(() -> service.createClient(request)).isInstanceOf(OperationException.class);
        verifyNoInteractions(clients, countries, maritalStatuses, municipalities);
    }

    @Test
    void normalizesPersonalDataAndKeepsDpiAsString() {
        var request = Hu05Fixtures.client("0123456789012");
        request.setFirstName("  Ana  ");
        request.setEmail("ANA@example.test");
        request.setMunicipalityId(null);
        when(countries.findById(1L)).thenReturn(Optional.of(CountryEntity.builder().id(1L).build()));
        when(maritalStatuses.findById(1L)).thenReturn(Optional.of(MaritalStatusEntity.builder().id(1L).build()));
        when(clients.saveAndFlush(any())).thenAnswer(invocation -> {
            ClientUserEntity entity = invocation.getArgument(0);
            entity.setVersion(0L);
            return entity;
        });
        var response = service.createClient(request);
        assertThat(response.getDpi()).isEqualTo("0123456789012");
        assertThat(response.getFirstName()).isEqualTo("Ana");
        assertThat(response.getEmail()).isEqualTo("ana@example.test");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void rejectsUnknownCatalogWithoutSaving() {
        var request = Hu05Fixtures.client("1234567890123");
        when(maritalStatuses.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createClient(request))
                .isInstanceOfSatisfying(OperationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(clients, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleVersionOnDeactivationAndPreservesData() {
        var client = new ClientUserEntity();
        client.setVersion(2L);
        when(clients.findForUpdate("1234567890123")).thenReturn(Optional.of(client));
        assertThatThrownBy(() -> service.deactivateClient("1234567890123", 1L))
                .isInstanceOfSatisfying(OperationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(client.isActive()).isTrue();
        verify(clients, never()).saveAndFlush(any());
    }
}
