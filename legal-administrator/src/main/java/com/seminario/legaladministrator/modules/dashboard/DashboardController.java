package com.seminario.legaladministrator.modules.dashboard;

import com.seminario.legaladministrator.modules.dashboard.DashboardDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
public class DashboardController {

    private final DashboardService service;

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary());
    }

    @GetMapping("/reminders")
    public ResponseEntity<RemindersPage> reminders(
        @RequestParam(required = false) ReminderKind kind,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.remindersPage(kind, page, size));
    }
}
