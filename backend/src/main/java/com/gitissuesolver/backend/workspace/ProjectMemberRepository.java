package com.gitissuesolver.backend.workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.gitissuesolver.backend.auth.Role;
import java.util.*;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);
    @Query("select m.role from ProjectMember m where m.projectId = :projectId and m.userId = :userId")
    Optional<Role> findRole(@Param("projectId") Long projectId, @Param("userId") Long userId);
    List<ProjectMember> findByProjectId(Long projectId);
    void deleteByProjectIdAndUserId(Long projectId, Long userId);
}
