package vn.teasmart.backend.auth;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.security.*;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.notification.OrderNotifications;
import vn.teasmart.backend.exception.GoogleAuthException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class GoogleAccountRulesTest {
    UserRepository users=mock(UserRepository.class); JwtService jwt=mock(JwtService.class);UserService profiles=mock(UserService.class);
    GoogleLoginChallengeStore challenges=new GoogleLoginChallengeStore();
    GoogleTokenVerifier verifier=mock(GoogleTokenVerifier.class);
    OrderNotifications notifications=mock(OrderNotifications.class);
    GoogleAuthService service=new GoogleAuthService(verifier,challenges,users,jwt,profiles,mock(PlatformTransactionManager.class),notifications);
    GoogleTokenVerifier.Identity identity=new GoogleTokenVerifier.Identity("fixture-sub","fixture@example.invalid","Fixture");
    User linked(String role,String status){User u=new User();u.setUserId(9L);u.setRole(role);u.setStatus(status);u.setGoogleSub(identity.sub());return u;}
    void login() {var challenge=challenges.issue(null);when(verifier.verify("fixture-token",challenge.nonce())).thenReturn(identity);service.login("fixture-token",challenge.binding());}
    @Test void existingEmailNeverAutoLinks() {
        when(users.findByGoogleSub(identity.sub())).thenReturn(Optional.empty());when(users.existsByEmail(identity.email())).thenReturn(true);
        assertThrows(GoogleAuthException.class,this::login);verify(users,never()).saveAndFlush(any());verify(jwt,never()).generateAccessToken(any());verifyNoInteractions(notifications);
    }
    @Test void linkedCustomerUsesSameAccountOnRepeatedLogin() {
        User u=linked("CUSTOMER","ACTIVE");when(users.findByGoogleSub(identity.sub())).thenReturn(Optional.of(u));
        login();login();verify(jwt,times(2)).generateAccessToken(u);verify(notifications,times(2)).loggedIn(u,true);verify(notifications,never()).welcome(any());verify(users,never()).saveAndFlush(any());
    }
    @Test void newCustomerHasNoPasswordOrPhoneAndNoAdminRole() {
        when(users.findByGoogleSub(identity.sub())).thenReturn(Optional.empty());when(users.saveAndFlush(any())).thenAnswer(call->{User u=call.getArgument(0);u.setUserId(10L);return u;});login();
        var captor=org.mockito.ArgumentCaptor.forClass(User.class);verify(users).saveAndFlush(captor.capture());User u=captor.getValue();
        assertEquals("CUSTOMER",u.getRole());assertEquals("ACTIVE",u.getStatus());assertNull(u.getPasswordHash());assertNull(u.getPhone());assertEquals(identity.sub(),u.getGoogleSub());verify(notifications).welcome(u);verify(notifications).loggedIn(u,true);
    }
    @Test void adminAndInactiveCannotUseGoogle() {
        when(users.findByGoogleSub(identity.sub())).thenReturn(Optional.of(linked("ADMIN","ACTIVE")));assertThrows(GoogleAuthException.class,this::login);
        when(users.findByGoogleSub(identity.sub())).thenReturn(Optional.of(linked("CUSTOMER","INACTIVE")));assertThrows(GoogleAuthException.class,this::login);verify(jwt,never()).generateAccessToken(any());verifyNoInteractions(notifications);
    }
}
