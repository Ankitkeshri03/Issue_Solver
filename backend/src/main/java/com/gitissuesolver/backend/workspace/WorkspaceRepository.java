package com.gitissuesolver.backend.workspace;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    Optional<Workspace> findByProvisioningRequestId(String requestId);
    @Query("select w from Workspace w where exists (select m.id from WorkspaceMember m where m.workspaceId = w.id and m.userId = :userId)")
    List<Workspace> findVisible(@Param("userId") Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> lockById(@Param("id") Long id);
}
