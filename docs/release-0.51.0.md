# Dwarvenpick v0.51.0

- Query admission releases reservations after startup failures. A dedicated scheduler renews heartbeats for active workers and CSV exports.
- Query simulations are confined to test configuration. Single-statement PostgreSQL reads using a read-only profile fetch through a JDBC cursor. Result cells fit their UTF-8 or hex byte budget and numeric sorting respects the JDBC type.
- The Explorer looks up literal JDBC object names and shares concurrent metadata loads per connection and profile. The bounded cache is invalidated by connection changes made on that backend.
- Results require current permission to query with the profile used for execution. CSV records completion after writing the response.
- API clients can retry submissions with an optional `Idempotency-Key` while the execution record is retained.

The optional `DWARVENPICK_DATASOURCE_MAX_POOL_CONNECTIONS_PER_TARGET` setting accounts for prepared pool capacity across profiles and connections that use the same configured hostname and port. Set the limit per backend replica and budget separately for target aliases. A pool that would exceed the limit is rejected. The default value of zero disables the limit.
