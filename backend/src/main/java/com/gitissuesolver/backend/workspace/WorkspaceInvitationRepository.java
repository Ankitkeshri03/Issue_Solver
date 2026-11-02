package com.gitissuesolver.backend.workspace;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, Long> {
    List<WorkspaceInvitation> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
    @Query("select i.workspaceId from WorkspaceInvitation i where i.tokenHash = :hash")
    Optional<Long> findWorkspaceIdByTokenHash(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkspaceInvitation i where i.tokenHash = :hash")
    Optional<WorkspaceInvitation> lockByTokenHash(@Param("hash") String hash);
}
