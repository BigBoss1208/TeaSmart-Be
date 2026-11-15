package vn.teasmart.backend.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import vn.teasmart.backend.dto.response.UserResponse;
import vn.teasmart.backend.service.ProfileService;
@RestController
public class ProfileController {
 private final ProfileService service;public ProfileController(ProfileService service){this.service=service;}
 public record ProfileRequest(@NotBlank @Size(max=100) String fullName,@NotBlank @Size(max=30) String phone){}
 @PatchMapping("/api/users/me/profile") public UserResponse profile(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ProfileRequest r){return service.updateProfile(Long.valueOf(jwt.getSubject()),r.fullName(),r.phone());}
 public record PhoneRequest(@NotBlank @Size(max=30) String phone){}
 @PatchMapping("/api/users/me/phone") public UserResponse phone(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PhoneRequest r){return service.updatePhone(Long.valueOf(jwt.getSubject()),r.phone());}
 public record PasswordRequest(@NotBlank @Size(max=200) String currentPassword,@NotBlank @Size(min=8,max=72) String newPassword){}
 @PatchMapping("/api/users/me/password") public ResponseEntity<Void> password(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PasswordRequest r){service.changePassword(Long.valueOf(jwt.getSubject()),r.currentPassword(),r.newPassword());return ResponseEntity.noContent().build();}
 @PostMapping(value="/api/users/me/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public UserResponse avatar(@AuthenticationPrincipal Jwt jwt,@RequestPart("file") MultipartFile file){return service.avatar(Long.valueOf(jwt.getSubject()),file);}
 @GetMapping("/api/avatars/{key}") public ResponseEntity<byte[]> avatar(@PathVariable String key){var bytes=service.readAvatar(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(key.endsWith(".jpg")?MediaType.IMAGE_JPEG:MediaType.IMAGE_PNG).body(bytes);}
}
