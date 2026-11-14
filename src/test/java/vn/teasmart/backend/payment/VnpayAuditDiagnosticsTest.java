package vn.teasmart.backend.payment;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import vn.teasmart.backend.service.VnpayCheckoutAudit;
import java.sql.SQLException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
class VnpayAuditDiagnosticsTest {
    @Test void missingTableIsClassifiedWithoutLeakingSqlOrExceptionText() {
        var sink=new ListAppender<ILoggingEvent>(); sink.start();
        var logger=(Logger)LoggerFactory.getLogger(VnpayCheckoutAudit.class); logger.addAppender(sink);
        try {
            var audit=new VnpayCheckoutAudit(new JdbcTemplate(){
                @Override public int update(String sql,Object... args){
                    throw new BadSqlGrammarException("test","sensitive-sql",new SQLException("sensitive-secret","42S02",1146));
                }
            });
            assertDoesNotThrow(()->audit.callback(VnpayCheckoutAudit.Source.RETURN,null,"INVALID","REJECTED"));
            String messages=sink.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertTrue(messages.contains("sqlState=42S02 vendorCode=1146"));
            assertFalse(messages.contains("sensitive-secret")); assertFalse(messages.contains("sensitive-sql"));
        } finally {logger.detachAppender(sink);sink.stop();}
    }
}