package com.cinesync.ticketing.app.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** The answer of the paginated lists to a wrong {@code page} or {@code limit}: the single error envelope. */
@RestControllerAdvice(assignableTypes = {NotificationsController.class, TicketsController.class})
public class PaginationExceptionHandler {

    @ExceptionHandler({InvalidPaginationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidPagination(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", new InvalidPaginationException().getMessage()));
    }
}
