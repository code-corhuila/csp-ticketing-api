package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.TicketingFulfillment.FulfillmentStatus;
import com.cinesync.ticketing.core.port.in.PageResult;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase.Concession;
import com.cinesync.ticketing.core.port.in.TicketQueryUseCase.TicketView;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketsControllerTest {

    private final TicketQueryUseCase query = mock(TicketQueryUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TicketsController(query))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new PaginationExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new TicketingConfiguration().objectMapper()))
            .build();
    private final UUID user = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

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

    private TicketView view(boolean hasPdf, FulfillmentStatus status) {
        return new TicketView(ticketId, UUID.randomUUID(), UUID.randomUUID(), "Example Movie", "Sala 1",
                Instant.parse("2026-12-01T20:00:00Z"), List.of("A1", "A2"), List.of(new Concession("Popcorn", 2)), "CSP-1",
                hasPdf, status, Instant.parse("2026-11-06T20:00:00Z"));
    }

    @Test
    void theListCarriesTheFieldsOfTheContractAndThePaginationMeta() throws Exception {
        when(query.listMine(user, 2, 5)).thenReturn(new PageResult<>(List.of(view(true, FulfillmentStatus.SENT)), 2, 5, 11));

        mvc.perform(get("/api/v1/ticketing/me/tickets").param("page", "2").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ticketId.toString()))
                .andExpect(jsonPath("$.data[0].movieTitle").value("Example Movie"))
                .andExpect(jsonPath("$.data[0].roomName").value("Sala 1"))
                .andExpect(jsonPath("$.data[0].showtimeStartsAt").value("2026-12-01T20:00:00Z"))
                .andExpect(jsonPath("$.data[0].seatLabels[1]").value("A2"))
                .andExpect(jsonPath("$.data[0].concessions[0].name").value("Popcorn"))
                .andExpect(jsonPath("$.data[0].concessions[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].concessions[0].unitPriceCents").doesNotExist())
                .andExpect(jsonPath("$.data[0].qrPayload").value("CSP-1"))
                .andExpect(jsonPath("$.data[0].deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-06T20:00:00Z"))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.limit").value(5))
                .andExpect(jsonPath("$.meta.total").value(11))
                .andExpect(jsonPath("$.meta.totalPages").value(3));
    }

    @Test
    void thePdfUrlIsThePathOfTheDownloadAndNullUntilThePdfExists() throws Exception {
        when(query.listMine(user, 1, 20)).thenReturn(new PageResult<>(List.of(view(true, FulfillmentStatus.PENDING)), 1, 20, 1));
        mvc.perform(get("/api/v1/ticketing/me/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pdfUrl").value("/api/v1/ticketing/me/tickets/" + ticketId + "/pdf"));

        when(query.listMine(user, 1, 20)).thenReturn(new PageResult<>(List.of(view(false, FulfillmentStatus.PENDING)), 1, 20, 1));
        mvc.perform(get("/api/v1/ticketing/me/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pdfUrl").value((Object) null));
    }

    @Test
    void aUserWithoutTicketsGetsAnEmptyList() throws Exception {
        when(query.listMine(user, 1, 20)).thenReturn(new PageResult<>(List.of(), 1, 20, 0));

        mvc.perform(get("/api/v1/ticketing/me/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void aWrongPageOrLimitIsABadRequestInTheErrorEnvelope() throws Exception {
        for (String[] params : new String[][]{{"page", "0"}, {"limit", "0"}, {"limit", "101"}, {"page", "abc"}}) {
            mvc.perform(get("/api/v1/ticketing/me/tickets").param(params[0], params[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        verify(query, never()).listMine(any(), anyInt(), anyInt());
    }
}
