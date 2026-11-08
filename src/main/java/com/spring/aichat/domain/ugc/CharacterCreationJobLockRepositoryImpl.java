package com.spring.aichat.domain.ugc;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;

class CharacterCreationJobLockRepositoryImpl implements CharacterCreationJobLockRepository {
    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<CharacterCreationJob> findByIdForUpdate(Long id) {
        return FreshLock.findForUpdate(em, CharacterCreationJob.class, id);
    }
}
