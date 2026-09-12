package com.seminario.legaladministrator.modules.users.service;

import com.seminario.legaladministrator.modules.users.UserPhoneEntity;
import com.seminario.legaladministrator.modules.users.repository.UserPhoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiredArgsConstructor
@Service
public class UserPhoneService {
    private final UserPhoneRepository userPhoneRepository;

    public List<UserPhoneEntity> findPhonesByUserDpi(String dpi) {
        return userPhoneRepository.findByUserSystemDpi(dpi);
    }

    public UserPhoneEntity savePhone(UserPhoneEntity userPhone) {
        return userPhoneRepository.save(userPhone);
    }

    public void deletePhone(Long id) {
        userPhoneRepository.deleteById(id);
    }
}