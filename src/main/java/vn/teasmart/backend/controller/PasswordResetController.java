package vn.teasmart.backend.controller;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.PasswordResetService;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {
    public static final String SENT="Nếu email đã được đăng ký, TeaSmart sẽ gửi mã OTP đặt lại mật khẩu đến hộp thư của bạn.";
    private final PasswordResetService service;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100), r->{
        Thread t=new Thread(r,"password-reset-mail"); t.setDaemon(true); return t;
    },new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Window> limits=new HashMap<>();
    private record Window(long start,int count) { }
    public PasswordResetController(PasswordResetService service) { this.service=service; }
    @PreDestroy public void close() { worker.shutdown(); }
    public record EmailRequest(@NotBlank @Email @Size(max=255) String email) { }
    public record OtpRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String challenge, @NotBlank @Pattern(regexp="[0-9]{6}") String otp) { }
    public record TokenRequest(@NotBlank @Size(max=128) String token) { }
    public record ResetRequest(@NotBlank @Size(max=128) String token,
            @NotBlank @Size(min=8,max=72) String password, @NotBlank @Size(max=72) String confirmPassword) { }
    private synchronized boolean allow(String key,int maximum) {
        long now=System.currentTimeMillis();
        limits.entrySet().removeIf(e->now-e.getValue().start()>900_000);
        Window w=limits.get(key);
        if (w==null) { if(limits.size()>=10000)return false; w=new Window(now,0); }
        if(w.count()>=maximum)return false;
        limits.put(key,new Window(w.start(),w.count()+1)); return true;
    }
    private ResponseEntity<Map<String,String>> message(int status,String code,String text) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",code,"message",text));
    }
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String,String>> forgot(@Valid @RequestBody EmailRequest body,HttpServletRequest request) {
        if(!service.enabled())return message(503,"PASSWORD_RESET_UNAVAILABLE","Khôi phục mật khẩu chưa được cấu hình. Vui lòng thử lại sau.");
        if(!allow("ip:"+request.getRemoteAddr(),10))return message(429,"RATE_LIMITED","Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút.");
        String email=body.email().trim().toLowerCase(Locale.ROOT);
        String challenge=service.newChallenge();
        if(allow("email:"+PasswordResetService.hash(email),3)) {
            try { worker.execute(()->{ try {service.request(email,challenge);} catch(Exception ignored) {
                LoggerFactory.getLogger(PasswordResetController.class).warn("PASSWORD_RESET_DELIVERY_FAILED");
            }}); } catch(RejectedExecutionException ignored) {
                return message(503,"PASSWORD_RESET_UNAVAILABLE","Hệ thống đang bận. Vui lòng thử lại sau.");
            }
        }
        // Same status/body and background processing for unknown, inactive and Google-only accounts.
        return ResponseEntity.status(202).header("Cache-Control","no-store").body(Map.of("code","PASSWORD_RESET_REQUESTED","message",SENT,"challenge",challenge));
    }
    @PostMapping("/reset-password/verify")
    public ResponseEntity<Map<String,String>> verify(@Valid @RequestBody OtpRequest body,HttpServletRequest request) {
        if(!allow("otp:"+request.getRemoteAddr(),60))return message(429,"RATE_LIMITED","Vui lòng thử lại sau 15 phút.");
        String grant=service.verifyOtp(body.challenge(),body.otp());
        if(grant==null)return message(400,"INVALID_OTP","Mã OTP không hợp lệ, đã hết hạn hoặc đã nhập sai 5 lần. Vui lòng yêu cầu mã mới.");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("code","OTP_VERIFIED","token",grant));
    }
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String,String>> reset(@Valid @RequestBody ResetRequest body,HttpServletRequest request) {
        if(!allow("token:"+request.getRemoteAddr(),60))return message(429,"RATE_LIMITED","Vui lòng thử lại sau 15 phút.");
        if(!body.password().equals(body.confirmPassword()) || body.password().getBytes(StandardCharsets.UTF_8).length>72)
            return message(400,"INVALID_PASSWORD","Mật khẩu phải khớp, dài ít nhất 8 ký tự và không quá 72 byte UTF-8.");
        return service.reset(body.token(),body.password())?message(200,"PASSWORD_RESET","Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.")
                :message(400,"INVALID_RESET_TOKEN",PasswordResetService.INVALID);
    }
}
