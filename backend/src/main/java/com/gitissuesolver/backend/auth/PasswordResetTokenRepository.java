package com.gitissuesolver.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    @Modifying
    @Query("delete from PasswordResetToken t where t.user.id = :userId")
    void deleteForUser(@Param("userId") Long userId);
}
