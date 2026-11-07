package com.cinesync.ticketing.core.model;

/** What the client is told about (HU-TICKETING-005): the four types of the contract. */
public enum NotificationType {
    HOLD_EXPIRY_WARNING,
    BOOKING_CONFIRMED,
    RESERVATION_EXPIRED,
    TICKET_ISSUED
}
