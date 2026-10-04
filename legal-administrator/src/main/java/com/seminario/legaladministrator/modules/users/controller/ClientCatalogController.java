package com.seminario.legaladministrator.modules.users.controller;

import com.seminario.legaladministrator.modules.locations.repository.CountryRepository;
import com.seminario.legaladministrator.modules.locations.repository.MunicipalityRepository;
import com.seminario.legaladministrator.modules.users.repository.MaritalStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/catalogs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class ClientCatalogController {
    private final CountryRepository countries;
    private final MaritalStatusRepository maritalStatuses;
    private final MunicipalityRepository municipalities;
    public record CatalogItem(Long id, String name) { }
    public record MunicipalityItem(Long id, String name, String departmentCode) { }

    @GetMapping("/countries")
    public List<CatalogItem> countries() {
        return countries.findAll(Sort.by("name")).stream()
                .map(c -> new CatalogItem(c.getId(), c.getName())).toList();
    }
    @GetMapping("/marital-statuses")
    public List<CatalogItem> maritalStatuses() {
        return maritalStatuses.findAll(Sort.by("name")).stream()
                .map(c -> new CatalogItem(c.getId(), c.getName())).toList();
    }
    @GetMapping("/municipalities")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<MunicipalityItem> municipalities() {
        return municipalities.findAll(Sort.by("name")).stream()
                .map(c -> new MunicipalityItem(c.getId(), c.getName(), c.getDepartment().getNumericalCode()))
                .toList();
    }
}
