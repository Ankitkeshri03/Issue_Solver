package com.gitissuesolver.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.EntityGraph;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    @Override
    @EntityGraph(attributePaths = {"repo", "assignedTo", "createdBy"})
    Optional<Ticket> findById(Long id);
    Optional<Ticket> findByRepoIdAndGithubIssueNumber(Long repoId, Integer githubIssueNumber);
    @Query("select t.repo.projectId from Ticket t where t.id = :id")
    Optional<Long> findProjectId(@Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> lockById(@Param("id") Long id);
    @EntityGraph(attributePaths = {"repo", "assignedTo", "createdBy"})
    List<Ticket> findByRepoId(Long repoId);
    List<Ticket> findByAssignedToId(Long userId);
    List<Ticket> findByStatus(TicketStatus status);
    List<Ticket> findByStatusIn(List<TicketStatus> statuses);

    // Async agent methods run outside the request's Hibernate session, so the lazily-loaded
    // Repo association must be fetched eagerly up front or accessing it throws
    // LazyInitializationException ("no session").
    @Query("select t from Ticket t join fetch t.repo left join fetch t.assignedTo left join fetch t.createdBy where t.id = :id")
    Optional<Ticket> findByIdWithRepo(@Param("id") Long id);

    @EntityGraph(attributePaths = {"repo", "assignedTo", "createdBy"})
    @Query("select t from Ticket t where t.githubPullRequest = false and exists (select p.id from Project p, WorkspaceMember w where p.id = t.repo.projectId and w.workspaceId = p.workspaceId and w.userId = :userId and (w.admin = true or exists (select m.id from ProjectMember m where m.projectId = p.id and m.userId = :userId))) and (:repoId is null or t.repo.id = :repoId) and (:assignedId is null or t.assignedTo.id = :assignedId)")
    List<Ticket> findVisible(@Param("userId") Long userId, @Param("repoId") Long repoId, @Param("assignedId") Long assignedId);

    @Transactional
    @Modifying
    @Query("update Ticket t set t.status = :next where t.id = :id and t.status in :allowed")
    int transition(@Param("id") Long id, @Param("allowed") List<TicketStatus> allowed, @Param("next") TicketStatus next);

    @Transactional
    @Modifying
    @Query("update Ticket t set t.githubPullRequest = :pullRequest, t.githubIssueClosed = :closed, t.status = :next, t.qaVerifiedAt = case when :next = com.gitissuesolver.backend.issue.TicketStatus.OPEN then null else t.qaVerifiedAt end, t.qaVerifiedBy = case when :next = com.gitissuesolver.backend.issue.TicketStatus.OPEN then null else t.qaVerifiedBy end, t.qaReopened = case when :next = com.gitissuesolver.backend.issue.TicketStatus.OPEN then true else t.qaReopened end where t.id = :id and t.status = :expected")
    int syncGitHubState(@Param("id") Long id, @Param("expected") TicketStatus expected,
                        @Param("next") TicketStatus next, @Param("pullRequest") boolean pullRequest,
                        @Param("closed") boolean closed);

    @Query("select count(t) from Ticket t where t.repo.projectId = :projectId and t.assignedTo.id = :userId and t.status in (com.gitissuesolver.backend.issue.TicketStatus.ANALYZING, com.gitissuesolver.backend.issue.TicketStatus.PLAN_APPROVED, com.gitissuesolver.backend.issue.TicketStatus.IMPLEMENTING, com.gitissuesolver.backend.issue.TicketStatus.TESTING)")
    long countRunningForMember(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Modifying @Transactional
    @Query("update Ticket t set t.assignedTo = null where t.repo.projectId = :projectId and t.assignedTo.id = :userId")
    int unassignProjectMember(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
