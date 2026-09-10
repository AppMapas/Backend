package com.seminario.legaladministrator.modules.locations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "department")
public class DepartmentEntity {
    @Id
    @Column(name = "numerical_code", length = 10, nullable = false)
    private String numericalCode;

    @Column(nullable = false, name = "name")
    private String name;
}