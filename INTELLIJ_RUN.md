# Run TeaSmart Backend in IntelliJ IDEA

The shared `TeaSmart Backend` configuration below still targets the primary
`teasmart` database. For presentation/testing, use a separate local configuration
with the `demo` profile described in [INTELLIJ_DEMO.md](INTELLIJ_DEMO.md); do not
press Run with the shared configuration until its primary schema has separately
been maintained by the database owner.

Open `backend/pom.xml` as a Maven project and load Maven dependencies. Select
`TeaSmart Backend` in Run/Debug Configurations. This shared Java Application
configuration runs `vn.teasmart.backend.TeaSmartApplication` directly; it does
not launch Maven, PowerShell, or a background service.

## JDK and module

The project requires Java 17 (`pom.xml`). The configuration uses the existing
local JDK at `C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot`.
On another computer, select an installed JDK 17 in the configuration.
In File > Project Structure, also select JDK 17 for Project SDK and module
`teasmart-backend` (the existing local IDE project currently selects SDK 21).
Reimport Maven so language level/target bytecode follows the POM.
Working directory is the backend project directory; no Spring profile is selected.
Windows classpath shortening uses a Java argument file.

## Environment

The shared configuration fixes these non-secret values:

- `DB_URL=jdbc:mysql://127.0.0.1:3306/teasmart`
- `SERVER_PORT=8080`
- `GOOGLE_AUTH_ENABLED=true`
- `GOOGLE_ALLOWED_ORIGINS=http://localhost:3000`

It inherits the environment of IntelliJ. Required existing values are:

- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET_BASE64`
- `JWT_ISSUER`
- `GOOGLE_CLIENT_ID` (public OAuth Web Client ID, identical to the frontend)

Do not enter passwords or JWT secrets into the shared `.run` file. Existing
Windows User variables are available only if IntelliJ was started after they
were configured; fully exit/reopen IntelliJ if necessary. This setup does not
change Windows User/System variables.

If credentials must be entered in IntelliJ instead, first duplicate this
configuration and uncheck **Store as project file** in the duplicate. Use a
distinct name such as `TeaSmart Backend (Local)` and enter secrets only in that
local configuration's Environment variables dialog. Its settings must remain
under the Git-ignored `.idea` directory, never under `.run`. Do not commit them.
The normal shared `TeaSmart Backend` can use inherited credentials; the public
Google Client ID may be entered into its Environment variables dialog.

Avoid datasource/profile overrides in VM options, program arguments, or
`SPRING_DATASOURCE_URL`, `SPRING_APPLICATION_JSON`, `SPRING_PROFILES_ACTIVE`,
`SPRING_CONFIG_LOCATION`, `SPRING_CONFIG_ADDITIONAL_LOCATION`, `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, and `MAVEN_OPTS`. Clear only unintended overrides in the
selected local Run Configuration. Do not expose secrets in screenshots/logs.

Optional existing settings remain unchanged: JWT expiration, private review
image storage, local review AI URL/timeouts, and VNPay Sandbox variables.
VNPay is disabled by default unless its existing environment explicitly enables
it. No Google Client Secret is used. No migration runs during application startup.

## Run and Stop

1. Ensure port 8080 is free; stop an existing identified TeaSmart server through
   its owning terminal/IDE rather than launching a second server.
2. Select `TeaSmart Backend`, verify JDK/module/environment, and press Run.
3. In the IntelliJ console verify JDBC URL ends in `/teasmart`, Hibernate validates
   successfully, and Tomcat starts on 8080. Do not proceed on a different database.
4. Check `GET http://localhost:8080/api/categories` (read-only).
5. Press Stop in IntelliJ. Verify with PowerShell:
   `Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue`.
   No result means the port is released.

Use this configuration for normal backend operation. The Maven wrapper remains
available for build/tests; do not use `spring-boot:run` as a parallel server.
Google Login on the main database can create a customer account; test it only
with explicit authorization. No test data, password reset, or SQL migration is
part of this Run Configuration.
