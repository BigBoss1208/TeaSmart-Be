package vn.teasmart.backend.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.teasmart.backend.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedByUserId(Long userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
