package com.apexchat.room;

import com.apexchat.common.ConflictException;
import com.apexchat.common.InvalidRequestException;
import com.apexchat.common.NotFoundException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and looks up rooms; announces new rooms to every connected client. */
@Service
public class RoomService {

  static final String ROOMS_TOPIC = "/topic/rooms";

  private final ChatRoomRepository rooms;
  private final SimpMessagingTemplate broker;
  private final Clock clock;

  public RoomService(ChatRoomRepository rooms, SimpMessagingTemplate broker, Clock clock) {
    this.rooms = rooms;
    this.broker = broker;
    this.clock = clock;
  }

  /** Turns a display name like "Design Crit!" into a URL-safe slug like "design-crit". */
  public static String slugify(String name) {
    String ascii =
        Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    String slug =
        ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    return slug.length() > 40 ? slug.substring(0, 40).replaceAll("-+$", "") : slug;
  }

  @Transactional(readOnly = true)
  public List<RoomDto> listRooms() {
    return rooms.findAllByOrderByIdAsc().stream().map(RoomDto::from).toList();
  }

  @Transactional(readOnly = true)
  public ChatRoom require(String slug) {
    return rooms.findBySlug(slug).orElseThrow(() -> NotFoundException.room(slug));
  }

  @Transactional
  public RoomDto create(CreateRoomRequest request, String createdBy) {
    String slug = slugify(request.name());
    if (slug.length() < 2) {
      throw new InvalidRequestException(
          "INVALID_ROOM_NAME", "Room names need at least two letters or digits");
    }
    if (rooms.existsBySlug(slug)) {
      throw new ConflictException("ROOM_EXISTS", "A room called #" + slug + " already exists");
    }
    String description = request.description() == null ? "" : request.description().trim();
    ChatRoom saved =
        rooms.save(
            new ChatRoom(slug, request.name().trim(), description, createdBy, clock.instant()));
    RoomDto dto = RoomDto.from(saved);
    broker.convertAndSend(ROOMS_TOPIC, dto);
    return dto;
  }
}
