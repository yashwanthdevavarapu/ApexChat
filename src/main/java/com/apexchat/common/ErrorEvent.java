package com.apexchat.common;

/** Error payload pushed to {@code /user/queue/errors} when a STOMP command fails. */
public record ErrorEvent(String code, String message) {

  public static ErrorEvent from(ChatException ex) {
    return new ErrorEvent(ex.code(), ex.getMessage());
  }
}
