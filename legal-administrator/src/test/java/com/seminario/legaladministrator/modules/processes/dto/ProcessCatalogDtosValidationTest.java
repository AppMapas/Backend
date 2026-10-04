package com.seminario.legaladministrator.modules.processes.dto;

import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessRequirementRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessCatalogDtosValidationTest {
    @Test
    void validatesNestedRequirementFields() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ProcessTypeRequest request = new ProcessTypeRequest(
                    "Titulación supletoria",
                    null,
                    List.of(new ProcessRequirementRequest(null, null, true, 0, null)),
                    null);

            var violations = validator.validate(request);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("requirements[0].requirementId",
                            "requirements[0].required",
                            "requirements[0].displayOrder");
        }
    }
}
