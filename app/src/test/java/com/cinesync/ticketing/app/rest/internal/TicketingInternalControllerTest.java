package com.cinesync.ticketing.app.rest.internal;

import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketDeliveryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketingInternalControllerTest {

    private final TicketDeliveryUseCase delivery = mock(TicketDeliveryUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TicketingInternalController(delivery)).build();
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
}
