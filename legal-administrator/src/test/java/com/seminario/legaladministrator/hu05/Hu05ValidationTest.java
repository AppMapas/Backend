package com.seminario.legaladministrator.hu05;

import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.CreateRequest;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class Hu05ValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345678901234", "123456789012x", "１２３４５６７８９０１２３", " 1234567890123"})
    void rejectsMalformedDpi(String dpi) {
        assertThat(FACTORY.getValidator().validate(Hu05Fixtures.client(dpi))).isNotEmpty();
        assertThatThrownBy(() -> InputRules.dpi(dpi)).isInstanceOf(OperationException.class);
    }

    @Test
    void acceptsCompletePersonalData() {
        assertThat(FACTORY.getValidator().validate(Hu05Fixtures.client("1234567890123"))).isEmpty();
    }

    @Test
    void rejectsMissingCatalogsAndAddressAndFutureBirthDate() {
        var client = Hu05Fixtures.client("1234567890123");
        client.setNationalityId(null);
        client.setMaritalStatusId(null);
        client.setExactAddress(" ");
        client.setBirthDate(LocalDate.now().plusDays(1));
        assertThat(FACTORY.getValidator().validate(client))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("nationalityId", "maritalStatusId", "exactAddress", "birthDate");
    }

    @Test
    void rejectsBothOrNeitherClientAndInvalidNestedData() {
        var id = UUID.randomUUID();
        var client = Hu05Fixtures.client("invalid");
        var validator = FACTORY.getValidator();
        assertThat(validator.validate(new CreateRequest(id, null, null, 1L, 0L, null, null))).isNotEmpty();
        assertThat(validator.validate(new CreateRequest(id, "1234567890123", client, 1L, 0L, null, null))).isNotEmpty();
        assertThat(validator.validate(new CreateRequest(id, null, client, 1L, 0L, null, null))).isNotEmpty();
    }

    @Test
    void requiresRequestKeyAndTemplateVersion() {
        assertThat(FACTORY.getValidator().validate(
                new CreateRequest(null, "1234567890123", null, 1L, null, null, null))).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 100000})
    void limitsPagination(int size) {
        assertThatThrownBy(() -> InputRules.page(0, size, Sort.by("dpi")))
                .isInstanceOf(OperationException.class);
    }

    @Test
    void rejectsControlCharactersAndOversizedQueries() {
        assertThatThrownBy(() -> InputRules.text("Ana\u0000")).isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> InputRules.search("a".repeat(101))).isInstanceOf(OperationException.class);
    }

    @Test
    void fingerprintChangesWhenPayloadChangesAndKeepsSameForRetry() {
        var guard = new CaseRequestGuard(null);
        var id = UUID.randomUUID();
        var original = new CreateRequest(id, "1234567890123", null, 1L, 0L, "Descripción", null);
        var changed = new CreateRequest(id, "1234567890123", null, 1L, 0L, "Otra", null);
        assertThat(guard.fingerprint(original)).hasSize(64).isEqualTo(guard.fingerprint(original));
        assertThat(guard.fingerprint(changed)).isNotEqualTo(guard.fingerprint(original));
    }
}
