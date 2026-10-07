package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
}
