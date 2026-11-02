package com.gitissuesolver.backend.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByGithubOwnerAndGithubRepo(String owner, String repo);
    Optional<Repo> findByOwnerUserIdAndGithubOwnerAndGithubRepo(Long userId, String owner, String repo);
    Optional<Repo> findByProjectIdAndGithubOwnerAndGithubRepo(Long projectId, String owner, String repo);
    List<Repo> findByProjectId(Long projectId);

    @Query("select r from Repo r where exists (select p.id from Project p, WorkspaceMember w where p.id = r.projectId and w.workspaceId = p.workspaceId and w.userId = :userId and (w.admin = true or exists (select m.id from ProjectMember m where m.projectId = p.id and m.userId = :userId)))")
    List<Repo> findVisible(@Param("userId") Long userId);
}
