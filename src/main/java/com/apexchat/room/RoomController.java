package com.apexchat.room;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST API for listing and creating rooms. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

  private final RoomService roomService;

  public RoomController(RoomService roomService) {
    this.roomService = roomService;
  }

  @GetMapping
  public List<RoomDto> list() {
    return roomService.listRooms();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomDto create(@Valid @RequestBody CreateRoomRequest request, Principal principal) {
    return roomService.create(request, principal.getName());
  }
}
