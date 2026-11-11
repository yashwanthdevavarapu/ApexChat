package com.apexchat.common;

/** A room or user referenced by the client does not exist. */
public class NotFoundException extends ChatException {

  public NotFoundException(String code, String message) {
    super(code, message);
  }

  public static NotFoundException room(String slug) {
    return new NotFoundException("ROOM_NOT_FOUND", "Room '" + slug + "' does not exist");
  }

  public static NotFoundException user(String username) {
    return new NotFoundException("USER_NOT_FOUND", "User '" + username + "' does not exist");
  }
}
