package com.cinesync.ticketing.app.rest.internal;

import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase.DispatchResult;
import com.cinesync.ticketing.core.port.in.TicketToRender;
import com.cinesync.ticketing.app.config.TicketingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketingInternalControllerTest {

    private final TicketDeliveryUseCase delivery = mock(TicketDeliveryUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TicketingInternalController(delivery))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new TicketingConfiguration().objectMapper()))
            .build();
    private final UUID ticketId = UUID.randomUUID();

    @Test
    void pdfUrl_isRegisteredWithTheCorrelationIdOfTheCall() throws Exception {
        mvc.perform(post("/internal/tickets/{id}/pdf", ticketId).header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pdfUrl\":\"https://files.example.com/a.pdf\"}"))
                .andExpect(status().isOk());

        verify(delivery).registerPdf(ticketId, "https://files.example.com/a.pdf", "corr-1");
    }

    @Test
    void pdfUrl_withoutAValue_isABadRequestInTheErrorEnvelope() throws Exception {
        mvc.perform(post("/internal/tickets/{id}/pdf", ticketId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pdfUrl\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(delivery, never()).registerPdf(any(), any(), any());
    }

    @Test
    void unknownTicket_isNotFound() throws Exception {
        doThrow(new TicketNotFoundException(ticketId)).when(delivery).registerPdf(eq(ticketId), any(), any());

        mvc.perform(post("/internal/tickets/{id}/pdf", ticketId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pdfUrl\":\"https://files.example.com/a.pdf\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"));
    }

    @Test
    void awaitingPdf_listsWhatTheWorkerNeedsToRenderTheDocuments() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(delivery.awaitingPdf(20)).thenReturn(List.of(new TicketToRender(ticketId, reservationId, "Example Movie",
                "Room 1", Instant.parse("2026-11-01T20:00:00Z"), List.of("A1", "A2"),
                List.of(new TicketToRender.Concession("Popcorn", 2, 500)), "CSP-1")));

        mvc.perform(get("/internal/tickets/awaiting-pdf").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.data[0].reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data[0].movieTitle").value("Example Movie"))
                .andExpect(jsonPath("$.data[0].showtimeStartsAt").value("2026-11-01T20:00:00Z"))
                .andExpect(jsonPath("$.data[0].seatNumbers[1]").value("A2"))
                .andExpect(jsonPath("$.data[0].concessions[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].qrPayload").value("CSP-1"));
    }

    @Test
    void awaitingPdf_limitOutsideOneToOneHundred_isABadRequest() throws Exception {
        mvc.perform(get("/internal/tickets/awaiting-pdf").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/tickets/awaiting-pdf").param("limit", "101")).andExpect(status().isBadRequest());

        verify(delivery, never()).awaitingPdf(any(Integer.class));
    }

    @Test
    void dispatch_returnsWhatHappened() throws Exception {
        when(delivery.dispatchDue(20)).thenReturn(new DispatchResult(2, 1, 0));

        mvc.perform(post("/internal/fulfillments/dispatch").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.retried").value(1))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void dispatch_limitOutsideOneToOneHundred_isABadRequest() throws Exception {
        mvc.perform(post("/internal/fulfillments/dispatch").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/fulfillments/dispatch").param("limit", "101")).andExpect(status().isBadRequest());

        verify(delivery, never()).dispatchDue(any(Integer.class));
    }
}
