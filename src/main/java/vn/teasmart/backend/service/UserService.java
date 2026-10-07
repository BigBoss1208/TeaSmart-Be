package vn.teasmart.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.dto.response.UserResponse;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User requireActiveUser(Long userId) {
        User user = repository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus())
                || !("CUSTOMER".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
            throw new BadCredentialsException("Authentication failed.");
        }
        return user;
    }

    public UserResponse getCurrentUser(Long userId) {
        User user = requireActiveUser(userId);
        return new UserResponse(user.getUserId(), user.getFullName(), user.getEmail(),
                user.getPhone(), user.getAvatarUrl(), user.getRole(), user.getCreatedAt());
    }
}
