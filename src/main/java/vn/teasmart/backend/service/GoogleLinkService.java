package vn.teasmart.backend.service;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.security.*;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.dto.response.AuthResponse;
@Service
public class GoogleLinkService {
 private final UserRepository users;private final UserService userService;private final PasswordEncoder encoder;private final GoogleTokenVerifier verifier;private final GoogleLoginChallengeStore challenges;private final JwtService jwt;private final TransactionTemplate tx;
 public GoogleLinkService(UserRepository users,UserService userService,PasswordEncoder encoder,GoogleTokenVerifier verifier,GoogleLoginChallengeStore challenges,JwtService jwt,PlatformTransactionManager manager){this.users=users;this.userService=userService;this.encoder=encoder;this.verifier=verifier;this.challenges=challenges;this.jwt=jwt;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public record LinkStatus(boolean linked,boolean canLink,String email){}
 public LinkStatus status(Long id){var user=userService.requireActiveUser(id);return new LinkStatus(user.getGoogleSub()!=null,"CUSTOMER".equals(user.getRole())&&user.getGoogleSub()==null,user.getGoogleEmail());}
 private void password(User u,String password){if(!"ACTIVE".equals(u.getStatus())||!"CUSTOMER".equals(u.getRole())||u.getPasswordHash()==null||password==null||password.getBytes(StandardCharsets.UTF_8).length>72||!encoder.matches(password,u.getPasswordHash()))throw new ProfileException(400,"LINK_AUTH_FAILED","Không thể xác thực tài khoản để liên kết Google.");}
 public AuthResponse attachVerified(Long id,GoogleTokenVerifier.Identity identity){
  try{return tx.execute(status->{var user=users.findLockedByUserId(id).orElseThrow(GoogleAuthException::failed);
   if(!"ACTIVE".equals(user.getStatus())||!"CUSTOMER".equals(user.getRole()))throw GoogleAuthException.failed();
   var other=users.findByGoogleSub(identity.sub()).orElse(null);
   if(other!=null&&!id.equals(other.getUserId())||user.getGoogleSub()!=null&&!user.getGoogleSub().equals(identity.sub()))throw new ProfileException(409,"GOOGLE_LINK_CONFLICT","Google đã được liên kết với tài khoản khác.");
   if(user.getGoogleSub()==null){user.setGoogleSub(identity.sub());user.setGoogleEmail(identity.email());user.setUpdatedAt(PaymentTime.now());users.saveAndFlush(user);}
   return new AuthResponse(jwt.generateAccessToken(user),"Bearer",jwt.getExpirationSeconds(),userService.getCurrentUser(id));
  });}catch(DataIntegrityViolationException e){throw new ProfileException(409,"GOOGLE_LINK_CONFLICT","Không thể liên kết Google này.");}
 }
 public AuthResponse link(Long id,String password,String token,String binding){password(userService.requireActiveUser(id),password);String nonce=challenges.require(binding);var identity=verifier.verify(token,nonce);challenges.consume(binding,nonce);try{return tx.execute(status->{var user=users.findLockedByUserId(id).orElseThrow(GoogleAuthException::failed);password(user,password);if(!user.getEmail().equalsIgnoreCase(identity.email())||user.getGoogleSub()!=null&&!user.getGoogleSub().equals(identity.sub()))throw new ProfileException(409,"GOOGLE_LINK_CONFLICT","Google không khớp tài khoản TeaSmart hoặc đã có liên kết khác.");var other=users.findByGoogleSub(identity.sub()).orElse(null);if(other!=null&&!id.equals(other.getUserId()))throw new ProfileException(409,"GOOGLE_LINK_CONFLICT","Google đã được liên kết với tài khoản khác.");if(user.getGoogleSub()==null){user.setGoogleSub(identity.sub());user.setGoogleEmail(identity.email());user.setUpdatedAt(PaymentTime.now());users.saveAndFlush(user);}return new AuthResponse(jwt.generateAccessToken(user),"Bearer",jwt.getExpirationSeconds(),userService.getCurrentUser(id));});}catch(DataIntegrityViolationException conflict){throw new ProfileException(409,"GOOGLE_LINK_CONFLICT","Không thể liên kết. Hãy kiểm tra trạng thái tài khoản rồi thử lại.");}}
}
