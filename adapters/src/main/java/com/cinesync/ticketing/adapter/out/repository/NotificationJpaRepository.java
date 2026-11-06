package com.cinesync.ticketing.adapter.out.repository;

import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.port.out.NotificationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationJpaRepository extends JpaRepository<Notification, UUID>, NotificationRepository {

    @Override
    @Query(value = "SELECT * FROM ticketing_fulfillment.notification WHERE user_id = :userId "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Notification> findPageByUserId(@Param("userId") UUID userId, @Param("limit") int limit, @Param("offset") int offset);
}
