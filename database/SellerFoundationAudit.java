import java.sql.*;
/** Run with MySQL JDBC driver. SELECT-only aggregate inventory; no customer fields. */
class SellerFoundationAudit {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("DB_URL");
  if(url==null || !url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/teasmart(?:\\?.*)?"))throw new IllegalStateException("Expected local teasmart");
  try(var c=DriverManager.getConnection(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))) {
   c.setReadOnly(true); c.setAutoCommit(false);
   for(String sql:java.util.List.of(
    "SELECT DATABASE() AS target",
    "SELECT status,reservation_status,COUNT(*) AS n FROM checkout_sessions GROUP BY status,reservation_status",
    "SELECT status,reconciliation_required,COUNT(*) AS n FROM payment_attempts GROUP BY status,reconciliation_required",
    "SELECT IF(a.checkout_session_id IS NULL,'LEGACY_ORDER','SESSION') AS owner_kind,s.status,s.reservation_status,a.gateway_response_code,a.gateway_transaction_status,COUNT(*) AS attempts,COUNT(DISTINCT a.checkout_session_id) AS sessions,COUNT(DISTINCT a.legacy_order_id) AS legacy_orders FROM payment_attempts a LEFT JOIN checkout_sessions s ON s.checkout_session_id=a.checkout_session_id WHERE a.status='UNKNOWN' GROUP BY owner_kind,s.status,s.reservation_status,a.gateway_response_code,a.gateway_transaction_status",
    "SELECT table_schema,table_name FROM information_schema.tables WHERE table_name IN ('shops','seller_applications') AND (table_schema='teasmart' OR table_schema LIKE 'teasmart_seller_test_%')",
    "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('shop_id','catalog_store_id')")) {
    try(var s=c.createStatement();var r=s.executeQuery(sql)) {System.out.println(sql);while(r.next()){for(int i=1;i<=r.getMetaData().getColumnCount();i++)System.out.print(r.getString(i)+" | ");System.out.println();}}
   } c.rollback();
  }
 }
}
