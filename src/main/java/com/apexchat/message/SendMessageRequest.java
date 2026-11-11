package com.apexchat.message;

/** Payload a client SENDs to post a message (STOMP or REST). */
public record SendMessageRequest(String content) {}
