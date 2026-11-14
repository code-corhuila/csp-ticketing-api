package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.UserContact;
import com.cinesync.ticketing.core.port.out.UserContactRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserContactJpaRepository extends JpaRepository<UserContact, UUID>, UserContactRepository {
    @Override
    Optional<UserContact> findByUserId(UUID userId);
}