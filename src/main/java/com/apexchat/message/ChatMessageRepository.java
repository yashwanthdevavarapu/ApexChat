package com.apexchat.message;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Keyset-paginated message queries: pass the oldest id you have as {@code before}. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

  List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long before, Pageable page);

  @Query(
      """
      select m from ChatMessage m
      where m.roomId is null
        and ((m.sender = :a and m.recipient = :b) or (m.sender = :b and m.recipient = :a))
        and m.id < :before
      order by m.id desc
      """)
  List<ChatMessage> findConversation(
      @Param("a") String a, @Param("b") String b, @Param("before") Long before, Pageable page);
}
