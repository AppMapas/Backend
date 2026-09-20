package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.RoleEntity;
import com.seminario.legaladministrator.modules.users.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private RoleService roleService;

    @Test
    void shouldReturnAllRoles() {
        RoleEntity admin = RoleEntity.builder().id(1L).name("ADMIN").description("Administrador").build();
        when(roleRepository.findAll()).thenReturn(List.of(admin));

        List<RoleEntity> roles = roleService.findAll();

        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).getName()).isEqualTo("ADMIN");
    }

    @Test
    void shouldReturnRoleByIdAndName() {
        RoleEntity role = RoleEntity.builder().id(2L).name("USER").description("Usuario").build();
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role));
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role));

        assertThat(roleService.findById(2L)).contains(role);
        assertThat(roleService.findByName("USER")).contains(role);
    }

    @Test
    void shouldSaveAndDeleteRole() {
        RoleEntity role = RoleEntity.builder().id(3L).name("AUDITOR").description("Auditor").build();
        when(roleRepository.save(role)).thenReturn(role);

        assertThat(roleService.saveRole(role)).isEqualTo(role);
        roleService.deleteRole(3L);

        verify(roleRepository).save(role);
        verify(roleRepository).deleteById(3L);
    }
}
