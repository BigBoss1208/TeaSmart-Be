import java.sql.*;
import java.util.Properties;

/** Diagnostic SELECTs only. Credentials remain in environment variables. */
class CheckoutReadOnlyAudit {
    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", System.getenv("DB_USERNAME"));
        props.setProperty("password", System.getenv("DB_PASSWORD"));
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "15000");
        props.setProperty("allowMultiQueries", "false");
        try (Connection c = DriverManager.getConnection(System.getenv("DB_URL"), props)) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                print(c, "SCHEMA", "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('checkout_sessions','payment_attempts','checkout_session_items','orders') AND column_name IN ('user_id','checkout_session_id','payment_attempt_id','order_id','status','reservation_status','last_reconciliation_at','product_id','quantity','gateway','gateway_response_code','gateway_transaction_status','created_at','updated_at','total_amount') ORDER BY table_name,ordinal_position");
                String affected = "(s.status IN ('AWAITING_PAYMENT','VERIFYING','RECONCILIATION_REQUIRED','PAID_PENDING_ORDER') OR EXISTS (SELECT 1 FROM payment_attempts x WHERE x.checkout_session_id=s.checkout_session_id AND x.status='UNKNOWN'))";
                print(c, "COUNTS", "SELECT COUNT(DISTINCT s.user_id) affected_users,COUNT(DISTINCT s.checkout_session_id) unresolved_sessions FROM checkout_sessions s WHERE " + affected);
                print(c, "UNKNOWN_USERS", "SELECT COUNT(DISTINCT s.user_id) unknown_users FROM checkout_sessions s JOIN payment_attempts a ON a.checkout_session_id=s.checkout_session_id WHERE a.status='UNKNOWN'");
                print(c, "SESSIONS", "SELECT s.user_id,s.checkout_session_id,s.status,s.reservation_status,s.total_amount,s.created_at,s.updated_at,a.payment_attempt_id,a.merchant_reference,a.gateway,a.amount attempt_amount,a.created_at attempt_created,a.expires_at attempt_expires,a.status attempt_status,a.last_reconciliation_at,a.gateway_response_code,a.gateway_transaction_status,o.order_id FROM checkout_sessions s LEFT JOIN payment_attempts a ON a.checkout_session_id=s.checkout_session_id LEFT JOIN orders o ON o.checkout_session_id=s.checkout_session_id WHERE " + affected + " AND s.user_id=74 AND s.checkout_session_id IN (9,15,16) ORDER BY s.checkout_session_id,a.payment_attempt_id");
                print(c, "ITEMS", "SELECT s.user_id,s.checkout_session_id,i.product_id,i.quantity FROM checkout_sessions s JOIN checkout_session_items i ON i.checkout_session_id=s.checkout_session_id WHERE " + affected + " ORDER BY s.user_id,s.checkout_session_id,i.product_id");
                print(c, "COD_COMPARISON", "SELECT o.user_id,COUNT(*) cod_orders,MAX(o.created_at) latest_cod_created,EXISTS(SELECT 1 FROM checkout_sessions s JOIN payment_attempts a ON a.checkout_session_id=s.checkout_session_id WHERE s.user_id=o.user_id AND a.status='UNKNOWN') has_unknown FROM orders o JOIN payments p ON p.order_id=o.order_id WHERE p.payment_method='COD' GROUP BY o.user_id ORDER BY has_unknown DESC,o.user_id");
            } finally { c.rollback(); }
        } catch (SQLException e) {
            System.err.println("AUDIT_FAILED SQLSTATE=" + e.getSQLState() + " CODE=" + e.getErrorCode());
            System.exit(1);
        }
    }
    static void print(Connection c,String label,String sql) throws SQLException {
        System.out.println(label);
        try (Statement st=c.createStatement()) {
            st.setQueryTimeout(15); st.setMaxRows(1000);
            try (ResultSet rs=st.executeQuery(sql)) {
                int n=rs.getMetaData().getColumnCount();
                for(int i=1;i<=n;i++) System.out.print((i==1?"":"\t")+rs.getMetaData().getColumnLabel(i));
                System.out.println();
                while(rs.next()) { for(int i=1;i<=n;i++) System.out.print((i==1?"":"\t")+rs.getString(i)); System.out.println(); }
            }
        }
    }
}
