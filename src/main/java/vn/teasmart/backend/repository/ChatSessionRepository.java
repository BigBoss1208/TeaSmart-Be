package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
}
