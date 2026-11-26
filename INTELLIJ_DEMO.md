# TeaSmart Demo Backend

Use a separate, local IntelliJ run configuration for database-backed demos. Do
not edit the shared `TeaSmart Backend` configuration, which intentionally points
to the primary `teasmart` database.

1. Duplicate the existing configuration and keep the duplicate local/unshared.
2. Set **Active profiles** to `demo` (or `SPRING_PROFILES_ACTIVE=demo`).
3. Provide `DEMO_DB_USERNAME` and `DEMO_DB_PASSWORD` for the test database only.
   The `demo` profile fixes JDBC URL to
   `jdbc:mysql://127.0.0.1:3306/teasmart_product_test`; it does not use `DB_URL`.
4. Remove unintended `SPRING_DATASOURCE_URL`, `SPRING_APPLICATION_JSON`, profile,
   config-location, `JAVA_TOOL_OPTIONS`, and `JDK_JAVA_OPTIONS` overrides.
5. Keep `ddl-auto=validate`; the app never applies SQL migrations at startup.

Before running, verify the JDBC URL/catalog in IntelliJ's environment and ensure
the `teasmart_product_test` schema is provisioned. Apply the approved migration
source manually only after inspecting the selected catalog. Never point this
profile at `teasmart`.

VNPay is disabled by default in this profile. Enable it only with explicitly
configured Sandbox credentials and callback URLs; never enable live gateway
credentials for a demo run.
