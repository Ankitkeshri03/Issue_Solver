package com.gitissuesolver.backend.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByGithubOwnerAndGithubRepo(String owner, String repo);
}
