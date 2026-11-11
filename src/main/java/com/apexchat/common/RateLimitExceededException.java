package com.apexchat.common;

/** The user is sending faster than the configured sliding-window limit allows. */
public class RateLimitExceededException extends ChatException {

  public RateLimitExceededException() {
    super("RATE_LIMITED", "You are sending messages too quickly. Please slow down.");
  }
}
