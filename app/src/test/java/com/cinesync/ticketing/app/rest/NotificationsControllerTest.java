package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.NotificationType;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase;
import com.cinesync.ticketing.core.port.in.NotificationQueryUseCase.NotificationView;
import com.cinesync.ticketing.core.port.in.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationsControllerTest {

    private final NotificationQueryUseCase query = mock(NotificationQueryUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new NotificationsController(query))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new TicketingConfiguration().objectMapper()))
            .build();
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void signIn() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", user.toString()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theListOfTheUserCarriesTypeMessageTimestampAndThePaginationMeta() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.listMine(user, 2, 5)).thenReturn(new PageResult<>(List.of(new NotificationView(id,
                NotificationType.BOOKING_CONFIRMED, "Tu reserva fue confirmada.", Instant.parse("2026-11-06T20:00:00Z"),
                Map.of("reservationId", "r-1"))), 2, 5, 11));

        mvc.perform(get("/api/v1/ticketing/me/notifications").param("page", "2").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].type").value("BOOKING_CONFIRMED"))
                .andExpect(jsonPath("$.data[0].message").value("Tu reserva fue confirmada."))
                .andExpect(jsonPath("$.data[0].timestamp").value("2026-11-06T20:00:00Z"))
                .andExpect(jsonPath("$.data[0].metadata.reservationId").value("r-1"))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.limit").value(5))
                .andExpect(jsonPath("$.meta.total").value(11))
                .andExpect(jsonPath("$.meta.totalPages").value(3));
    }

    @Test
    void withoutParametersItAsksForTheFirstPageOfTwenty() throws Exception {
        when(query.listMine(user, 1, 20)).thenReturn(new PageResult<>(List.of(), 1, 20, 0));

        mvc.perform(get("/api/v1/ticketing/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalPages").value(0));
    }

    @Test
    void aWrongPageOrLimitIsABadRequestInTheErrorEnvelope() throws Exception {
        for (String[] params : new String[][]{{"page", "0"}, {"page", "-1"}, {"limit", "0"}, {"limit", "101"}, {"page", "abc"}, {"limit", "x"}}) {
            mvc.perform(get("/api/v1/ticketing/me/notifications").param(params[0], params[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        verify(query, never()).listMine(any(), anyInt(), anyInt());
    }
}
