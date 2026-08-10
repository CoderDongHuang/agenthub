package com.agenthub.user.repository;

import com.agenthub.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<User> findByUsernameAndStatus(String username, String status);
    Optional<User> findByIdAndTenantId(Long id, Long tenantId);
    Page<User> findByTenantId(Long tenantId, Pageable pageable);
    long countByTenantId(Long tenantId);
}
