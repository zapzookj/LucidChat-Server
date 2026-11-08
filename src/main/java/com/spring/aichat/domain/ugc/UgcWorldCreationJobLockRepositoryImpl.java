package com.spring.aichat.domain.ugc;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;

class UgcWorldCreationJobLockRepositoryImpl implements UgcWorldCreationJobLockRepository {
    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<UgcWorldCreationJob> findByIdForUpdate(Long id) {
        return FreshLock.findForUpdate(em, UgcWorldCreationJob.class, id);
    }
}
