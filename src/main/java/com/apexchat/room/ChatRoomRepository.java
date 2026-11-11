package com.apexchat.room;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  Optional<ChatRoom> findBySlug(String slug);

  boolean existsBySlug(String slug);

  List<ChatRoom> findAllByOrderByIdAsc();
}
