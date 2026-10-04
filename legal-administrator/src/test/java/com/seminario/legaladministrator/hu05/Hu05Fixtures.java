package com.seminario.legaladministrator.hu05;

import com.seminario.legaladministrator.modules.users.dto.ClientUserRequestDto;

public final class Hu05Fixtures {
    private Hu05Fixtures() { }

    public static ClientUserRequestDto client(String dpi) {
        var request = new ClientUserRequestDto();
        request.setDpi(dpi);
        request.setFirstName("Ana María");
        request.setLastName("López Pérez");
        request.setEmail("ana@example.test");
        request.setPhone("+50255551234");
        request.setNationalityId(1L);
        request.setMaritalStatusId(1L);
        request.setExactAddress("Zona 1, Quetzaltenango");
        request.setMunicipalityId(1L);
        return request;
    }
}
