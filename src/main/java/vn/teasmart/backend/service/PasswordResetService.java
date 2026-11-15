package vn.teasmart.backend.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.notification.EmailNotification;

@Service
public class PasswordResetService {
    public static final String INVALID = "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.";
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder otpEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
    private final String dummyOtpHash = otpEncoder.encode("000000");
    private final SecureRandom random = new SecureRandom();
    public PasswordResetService(UserRepository users, JdbcTemplate jdbc, PasswordEncoder encoder,
            ApplicationEventPublisher events, @Value("${teasmart.password-reset.enabled:false}") boolean enabled,
            @Value("${teasmart.password-reset.frontend-url:http://localhost:3000}") String frontend,
            @Value("${teasmart.mail.enabled:false}") boolean mailEnabled) {
        if(enabled && !mailEnabled)throw new IllegalArgumentException("Password reset requires MAIL_ENABLED");
        this.users=users; this.jdbc=jdbc; this.encoder=encoder; this.events=events; this.enabled=enabled;
        URI uri=URI.create(frontend);
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) &&
                ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
            throw new IllegalArgumentException("Invalid password reset frontend origin");

    }
    public boolean enabled() { return enabled; }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private boolean eligible(User u) { return "ACTIVE".equals(u.getStatus()) && u.getPasswordHash()!=null && !u.getPasswordHash().isBlank(); }
    public String newChallenge() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    @Transactional
    public void request(String email) { request(email,newChallenge()); }
    @Transactional
    public void request(String email, String challenge) {
        if (!enabled) return;
        var found=users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) return;
        var u=users.findLockedByUserId(found.get().getUserId()).orElse(null);
        if (u==null || !eligible(u)) return;
        var now=LocalDateTime.now(ZoneOffset.UTC);
        var last=jdbc.query("SELECT issued_at FROM password_reset_tokens WHERE user_id=? FOR UPDATE", (rs,n)->rs.getTimestamp(1).toLocalDateTime(),u.getUserId());
        if (!last.isEmpty() && last.get(0).isAfter(now.minusMinutes(1))) return;
        if (challenge==null || !challenge.matches("[A-Za-z0-9_-]{43}")) return;
        String otp=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        jdbc.update("DELETE FROM password_reset_tokens WHERE user_id=?",u.getUserId());
        jdbc.update("INSERT INTO password_reset_tokens(user_id,token_hash,password_snapshot,issued_at,expires_at,otp_hash,otp_attempts) VALUES(?,?,?,?,?,?,0)",
                u.getUserId(),hash(challenge),u.getPasswordHash(),now,now.plusMinutes(5),otpEncoder.encode(otp));
        String html="<html><body style='font-family:Arial;background:#faf8f2;color:#124b36;padding:24px'>"
                +"<h1>TeaSmart</h1><h2>Mã xác minh đặt lại mật khẩu TeaSmart</h2>"
                +"<p>Mã OTP của bạn:</p><p style='font-size:32px;font-weight:bold;letter-spacing:8px'><strong>"+otp+"</strong></p>"
                +"<p>Mã hết hạn sau 5 phút. Không chia sẻ mã với bất kỳ ai.</p>"
                +"<p>Nếu bạn không yêu cầu, hãy bỏ qua email này. Mật khẩu của bạn chưa bị thay đổi.</p></body></html>";
        events.publishEvent(new EmailNotification(u.getEmail(),"Mã xác minh đặt lại mật khẩu TeaSmart",html));
    }
    private record Otp(long userId, String snapshot, LocalDateTime expires, String hash, int attempts) { }
    @Transactional
    public String verifyOtp(String challenge, String otp) {
        if (!enabled || challenge==null || !challenge.matches("[A-Za-z0-9_-]{43}") || otp==null || !otp.matches("[0-9]{6}")) return null;
        var owner=jdbc.query("SELECT user_id FROM password_reset_tokens WHERE token_hash=?",(rs,n)->rs.getLong(1),hash(challenge));
        if(owner.isEmpty()){otpEncoder.matches(otp,dummyOtpHash);return null;}
        // All issuance, OTP verification and reset use the same user -> token lock order.
        User u=users.findLockedByUserId(owner.get(0)).orElse(null);
        var rows=jdbc.query("SELECT user_id,password_snapshot,expires_at,otp_hash,otp_attempts FROM password_reset_tokens WHERE token_hash=? AND otp_verified_at IS NULL AND used_at IS NULL FOR UPDATE",
                (rs,n)->new Otp(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toLocalDateTime(),rs.getString(4),rs.getInt(5)),hash(challenge));
        if(rows.isEmpty()){otpEncoder.matches(otp,dummyOtpHash);return null;}
        Otp t=rows.get(0);
        boolean matches=otpEncoder.matches(otp,t.hash()==null?dummyOtpHash:t.hash());
        if(u==null || !eligible(u) || !t.snapshot().equals(u.getPasswordHash()) || t.hash()==null || t.attempts()>=5 || !t.expires().isAfter(LocalDateTime.now(ZoneOffset.UTC)))return null;
        if(!matches) {
            // Return normally so the failed-attempt counter commits.
            jdbc.update("UPDATE password_reset_tokens SET otp_attempts=otp_attempts+1 WHERE user_id=?",u.getUserId());
            return null;
        }
        String grant=newChallenge();
        var now=LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE password_reset_tokens SET token_hash=?,otp_hash=NULL,otp_verified_at=?,expires_at=? WHERE user_id=?",hash(grant),now,now.plusMinutes(5),u.getUserId());
        return grant;
    }
    private record Token(long userId, String snapshot, LocalDateTime expires, boolean used) { }
    private Token token(String raw) { return token(raw, false); }
    private Token token(String raw, boolean locked) {
        if (!enabled || raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        return jdbc.query("SELECT user_id,password_snapshot,expires_at,used_at FROM password_reset_tokens WHERE token_hash=? AND otp_verified_at IS NOT NULL" + (locked ? " FOR UPDATE" : ""),
                (rs,n)->new Token(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toLocalDateTime(),rs.getTimestamp(4)!=null),hash(raw))
                .stream().findFirst().orElse(null);
    }
    private boolean valid(Token t, User u) {
        return t!=null && u!=null && eligible(u) && !t.used() && t.expires().isAfter(LocalDateTime.now(ZoneOffset.UTC))
                && t.snapshot().equals(u.getPasswordHash());
    }
    @Transactional(readOnly=true)
    public boolean verify(String raw) {
        Token t=token(raw);
        return t!=null && valid(t,users.findById(t.userId()).orElse(null));
    }
    @Transactional
    public boolean reset(String raw, String password) {
        Token initial=token(raw); if (initial==null) return false;
        // Same lock order as issuance: the user serializes issuance and all concurrent redemption.
        User u=users.findLockedByUserId(initial.userId()).orElse(null);
        Token current=token(raw,true);
        if (!valid(current,u)) return false;
        u.setPasswordHash(encoder.encode(password)); u.setUpdatedAt(LocalDateTime.now()); users.saveAndFlush(u);
        jdbc.update("UPDATE password_reset_tokens SET used_at=? WHERE user_id=?",LocalDateTime.now(ZoneOffset.UTC),u.getUserId());
        return true;
    }
}
