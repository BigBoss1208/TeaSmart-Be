package vn.teasmart.backend.service;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.multipart.MultipartFile;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.storage.*;
import vn.teasmart.backend.dto.response.UserResponse;
import vn.teasmart.backend.exception.*;

@Service
public class ProfileService {
 private final UserRepository users; private final UserService userService; private final PasswordEncoder encoder;
 private final ReviewImageValidator validator; private final ReviewImageStorage storage;
 public ProfileService(UserRepository users,UserService userService,PasswordEncoder encoder,ReviewImageValidator validator,ReviewImageStorage storage){this.users=users;this.userService=userService;this.encoder=encoder;this.validator=validator;this.storage=storage;}
 private User locked(Long id){var u=users.findLockedByUserId(id).orElseThrow(()->new BadCredentialsException("Authentication failed."));if(!"ACTIVE".equals(u.getStatus())||!("CUSTOMER".equals(u.getRole())||"ADMIN".equals(u.getRole())))throw new BadCredentialsException("Authentication failed.");return u;}
 @Transactional public UserResponse updatePhone(Long id,String input){return updateProfile(id,null,input);}
 @Transactional public UserResponse updateProfile(Long id,String name,String input){
  if(name!=null&&(name.isBlank()||name.trim().length()>100))throw new ProfileException(400,"VALIDATION_ERROR","Họ tên bắt buộc và tối đa 100 ký tự.");
  String phone=vn.teasmart.backend.util.VietnamesePhone.normalize(input);
  if(phone==null||!phone.matches("^0[35789][0-9]{8}$"))throw new ProfileException(400,"VALIDATION_ERROR","Số điện thoại di động Việt Nam không hợp lệ.");
  var user=locked(id);
  if(phone.equals(user.getPhone())&&(name==null||name.trim().equals(user.getFullName())))return userService.getCurrentUser(id);
  if(!phone.equals(user.getPhone())&&users.existsByPhone(phone))throw new ProfileException(409,"DUPLICATE_PHONE","Số điện thoại đã được sử dụng.");
  if(name!=null)user.setFullName(name.trim());user.setPhone(phone);user.setUpdatedAt(PaymentTime.now());
  try{users.flush();}catch(org.springframework.dao.DataIntegrityViolationException e){throw new ProfileException(409,"DUPLICATE_PHONE","Không thể lưu số điện thoại này.");}
  return userService.getCurrentUser(id);
 }
 @Transactional public void changePassword(Long id,String current,String next){var user=locked(id);if(user.getPasswordHash()==null)throw new ProfileException(409,"PASSWORD_NOT_AVAILABLE","Tài khoản Google chưa có mật khẩu TeaSmart.");if(current==null||current.getBytes(StandardCharsets.UTF_8).length>72||!encoder.matches(current,user.getPasswordHash()))throw new ProfileException(400,"CURRENT_PASSWORD_INVALID","Mật khẩu hiện tại không chính xác.");if(next==null||next.length()<8||next.getBytes(StandardCharsets.UTF_8).length>72)throw new ProfileException(400,"INVALID_PASSWORD","Mật khẩu mới cần ít nhất8 ký tự, tối đa72 byte UTF-8.");user.setPasswordHash(encoder.encode(next));user.setUpdatedAt(PaymentTime.now());users.flush();}
 @Transactional public UserResponse avatar(Long id,MultipartFile file){var image=validator.normalize(List.of(file)).get(0);var key=storage.stage(image.bytes(),image.extension());try{var u=locked(id);var old=u.getAvatarUrl();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.discard(key);}@Override public void afterCommit(){if(old!=null&&old.startsWith("/api/avatars/"))storage.deleteAfterCommit(old.substring("/api/avatars/".length()));}});storage.finalizeFile(key);u.setAvatarUrl("/api/avatars/"+key);u.setUpdatedAt(PaymentTime.now());users.flush();return userService.getCurrentUser(id);}catch(RuntimeException e){storage.discard(key);throw e;}}
 @Transactional(readOnly=true) public byte[] readAvatar(String key){if(!key.matches("[0-9a-f-]{36}\\.(png|jpg)")||!users.existsByAvatarUrl("/api/avatars/"+key))throw new ResourceNotFoundException("Image not found.");return storage.read(key);}
}
