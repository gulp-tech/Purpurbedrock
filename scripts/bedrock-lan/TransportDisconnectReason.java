/*
 * Copyright 2026 CloudburstMC
 *
 * Licensed under the Apache License, Version 2.0.
 */
package org.cloudburstmc.netty.channel;

/**
 * Compatibility copy of the transport disconnect event consumed by the 26.60
 * protocol library. The 26.2 transport build predates this shared enum.
 */
public enum TransportDisconnectReason {
    CLOSED_BY_REMOTE_PEER,
    SHUTTING_DOWN,
    DISCONNECTED,
    TIMED_OUT,
    CONNECTION_REQUEST_FAILED,
    ALREADY_CONNECTED,
    NO_FREE_INCOMING_CONNECTIONS,
    INCOMPATIBLE_PROTOCOL_VERSION,
    IP_RECENTLY_CONNECTED,
    BAD_PACKET,
    QUEUE_TOO_LONG,
    SPLIT_QUEUE_TOO_LONG,
    ORDERING_QUEUE_TOO_LONG,
    ABORTED_BY_REMOTE_PEER,
    CONNECTION_LOST,
    TRANSPORT_FAILED
}
