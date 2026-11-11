package com.apexchat.common;

/** Base type for expected, user-facing chat errors. Carries a stable machine-readable code. */
public class ChatException extends RuntimeException {

  private final String code;

  public ChatException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
