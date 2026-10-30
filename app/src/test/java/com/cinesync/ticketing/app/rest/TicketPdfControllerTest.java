package com.cinesync.ticketing.app.rest;

import com.cinesync.ticketing.app.config.TicketingConfiguration;
import com.cinesync.ticketing.core.model.PdfStorageException;
import com.cinesync.ticketing.core.model.TicketNotFoundException;
import com.cinesync.ticketing.core.port.in.TicketPdfDownloadUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketPdfControllerTest {

    private final TicketPdfDownloadUseCase download = mock(TicketPdfDownloadUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TicketPdfController(download))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(new TicketingConfiguration().objectMapper()))
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

    @Test
    void theOwnerGetsThePdfAsAnAttachment() throws Exception {
        when(download.download(user, ticketId)).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mvc.perform(get("/api/v1/ticketing/me/tickets/{id}/pdf", ticketId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"ticket-" + ticketId + ".pdf\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().bytes(new byte[]{'%', 'P', 'D', 'F'}));
    }

    @Test
    void aTicketOfAnotherUserOrWithoutPdfIsNotFoundInTheErrorEnvelope() throws Exception {
        when(download.download(user, ticketId)).thenThrow(new TicketNotFoundException(ticketId));

        mvc.perform(get("/api/v1/ticketing/me/tickets/{id}/pdf", ticketId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aFailingStorageIsServiceUnavailableWithoutDetailsOfTheStorage() throws Exception {
        when(download.download(user, ticketId)).thenThrow(new PdfStorageException("connect to minio:9000 refused"));

        mvc.perform(get("/api/v1/ticketing/me/tickets/{id}/pdf", ticketId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PDF_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The ticket PDF is not available right now"));
    }
}
