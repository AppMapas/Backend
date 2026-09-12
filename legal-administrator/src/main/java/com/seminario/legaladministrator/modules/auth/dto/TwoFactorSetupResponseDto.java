package com.seminario.legaladministrator.modules.auth.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TwoFactorSetupResponseDto {
    private String secret;
    private String qrCodeUri;
    private String manualEntryKey;
}
