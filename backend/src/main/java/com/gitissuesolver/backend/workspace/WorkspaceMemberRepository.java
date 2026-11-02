package com.gitissuesolver.backend.workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);
    List<WorkspaceMember> findByWorkspaceId(Long workspaceId);
    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);
    boolean existsByUserIdAndAdminTrue(Long userId);
    boolean existsByWorkspaceIdAndUserIdAndAdminTrue(Long workspaceId, Long userId);
}
