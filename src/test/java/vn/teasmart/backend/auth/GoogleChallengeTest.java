package vn.teasmart.backend.auth;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.security.GoogleLoginChallengeStore;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.exception.GoogleAuthException;
import static org.junit.jupiter.api.Assertions.*;

class GoogleChallengeTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    @Test void expiryCapacityAndBrowserBinding() {
        var clock=new MutableClock();var store=new GoogleLoginChallengeStore(clock,1);
        var c=store.issue(null);assertEquals(c.nonce(),store.require(c.binding()));
        assertThrows(GoogleAuthException.class,()->store.require("another-browser"));
        assertThrows(GoogleAuthException.class,()->store.consume(c.binding(),"wrong"));
        assertThrows(GoogleAuthException.class,()->store.issue(null));
        clock.now=clock.now.plusSeconds(300);assertThrows(GoogleAuthException.class,()->store.require(c.binding()));
        assertNotNull(store.issue(null));
    }
    @Test void concurrentConsumeOnlyOneSucceeds() throws Exception {
        var store=new GoogleLoginChallengeStore();var c=store.issue(null);var pool=Executors.newFixedThreadPool(8);
        try {var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<8;i++)tasks.add(()->{
            try{store.consume(c.binding(),c.nonce());return true;}catch(GoogleAuthException e){return false;}});
            int success=0;for(var result:pool.invokeAll(tasks))if(result.get())success++;assertEquals(1,success);
            assertThrows(GoogleAuthException.class,()->store.require(c.binding()));
        }finally{pool.shutdownNow();}
    }
    @Test void allowedOriginAndDisabledConfiguration() {
        var config=new GoogleAuthConfig(true,"fixture-client","http://localhost:3000,https://shop.example.com");
        config.requireOrigin("http://localhost:3000");
        for(String origin:Arrays.asList(null,"null","https://evil.example","http://localhost:3000/"))
            assertThrows(GoogleAuthException.class,()->config.requireOrigin(origin));
        assertThrows(GoogleAuthException.class,()->new GoogleAuthConfig(false,"","").requireOrigin("http://localhost:3000"));
        assertThrows(IllegalArgumentException.class,()->new GoogleAuthConfig(true,"","") );
    }
}
