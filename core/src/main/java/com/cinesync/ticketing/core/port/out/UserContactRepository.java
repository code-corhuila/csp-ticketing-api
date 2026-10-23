package com.cinesync.ticketing.core.port.out;

import com.cinesync.ticketing.core.model.UserContact;

import java.util.Optional;
import java.util.UUID;

public interface UserContactRepository {
    Optional<UserContact> findByUserId(UUID userId);
    UserContact save(UserContact contact);
    Optional<UserContact> findById(UUID id);
}