package com.agenthub.user.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sys_permission")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "perm_name", nullable = false, length = 100)
    private String permName;

    @Column(name = "perm_code", nullable = false, unique = true, length = 100)
    private String permCode;

    @Column(length = 200)
    private String description;
}
