package com.agenthub.user.repository;

import com.agenthub.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRoleCode(String roleCode);
    Optional<Role> findByRoleCodeAndTenantId(String roleCode, Long tenantId);
    List<Role> findByIdInAndTenantId(Collection<Long> ids, Long tenantId);
    List<Role> findByTenantId(Long tenantId);
}
