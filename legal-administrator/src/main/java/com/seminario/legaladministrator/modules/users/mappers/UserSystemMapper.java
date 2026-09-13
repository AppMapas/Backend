package com.seminario.legaladministrator.modules.users.mappers;

import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.modules.users.dto.UserSystemResponseDto;
import org.springframework.stereotype.Component;

@Component
public class UserSystemMapper {
    public UserSystemResponseDto toResponseDto(UserSystemEntity user) {
        if (user == null) {
            return null;
        }

        return UserSystemResponseDto.builder()
                .dpi(user.getDpi())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .age(user.getAge())
                .email(user.getEmail())
                .maritalStatusName(user.getMaritalStatus() != null ? user.getMaritalStatus().getName() : null)
                .nationalityName(user.getNationality() != null ? user.getNationality().getName() : null)
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .createdAt(user.getCreatedAt())
                .twoFactorEnabled(user.isTwoFactorEnabled())
                .build();
    }
}
