package com.gitissuesolver.backend.workspace;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByWorkspaceId(Long workspaceId);
    @Query("select p from Project p where (:workspaceId is null or p.workspaceId = :workspaceId) and exists (select w.id from WorkspaceMember w where w.workspaceId = p.workspaceId and w.userId = :userId and (w.admin = true or exists (select m.id from ProjectMember m where m.projectId = p.id and m.userId = :userId)))")
    List<Project> findVisible(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);
}
