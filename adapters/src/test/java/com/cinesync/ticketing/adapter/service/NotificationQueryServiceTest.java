package com.cinesync.ticketing.adapter.service;

import com.cinesync.ticketing.core.model.Notification;
import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.out.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationQueryServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationQueryService service = new NotificationQueryService(repository);
    private final UUID user = UUID.randomUUID();

    private Notification notification() {
        Ticket ticket = Ticket.builder().id(UUID.randomUUID()).userId(user).reservationId(UUID.randomUUID())
                .showtimeId(UUID.randomUUID()).movieTitleSnapshot("Example Movie").roomNameSnapshot("Sala 1")
                .seatLabelsSnapshot(List.of("A1")).qrPayload("CSP").build();
        return Notification.ticketIssued(ticket);
    }

    @Test
    void thePageStartsAtTheOffsetOfItsNumberAndCarriesTheTotal() {
        when(repository.findPageByUserId(user, 20, 40)).thenReturn(List.of(notification(), notification()));
        when(repository.countByUserId(user)).thenReturn(43L);

        PageResult<NotificationView> page = service.listMine(user, 3, 20);

        assertThat(page.data()).hasSize(2);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.limit()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(43);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.data().get(0).message()).startsWith("Tu ticket para Example Movie");
    }

    @Test
    void aUserWithoutNotificationsGetsAnEmptyPage() {
        when(repository.findPageByUserId(user, 20, 0)).thenReturn(List.of());
        when(repository.countByUserId(user)).thenReturn(0L);

        PageResult<NotificationView> page = service.listMine(user, 1, 20);

        assertThat(page.data()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.totalPages()).isZero();
    }
}
