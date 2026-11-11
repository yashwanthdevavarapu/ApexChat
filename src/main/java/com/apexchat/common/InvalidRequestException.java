package com.apexchat.common;

/** The client sent something the server will not accept (blank message, too long, etc.). */
public class InvalidRequestException extends ChatException {

  public InvalidRequestException(String code, String message) {
    super(code, message);
  }
}
