package com.seminario.legaladministrator.model.locations;

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
@Table(name = "departament")
public class Departament {
    @Id
    @Column(name = "numerical_code", length = 10, nullable = false)
    private String numericalCode;

    @Column(nullable = false, name = "name")
    private String name;
}