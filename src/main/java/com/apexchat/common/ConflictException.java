package com.apexchat.common;

/** The request collides with existing state, such as a duplicate room or username. */
public class ConflictException extends ChatException {

  public ConflictException(String code, String message) {
    super(code, message);
  }
}
