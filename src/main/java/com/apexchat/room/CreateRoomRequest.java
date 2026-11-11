package com.apexchat.room;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/rooms}. */
public record CreateRoomRequest(
    @NotBlank @Size(min = 2, max = 40) String name, @Size(max = 200) String description) {}
