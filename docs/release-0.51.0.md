# Dwarvenpick v0.51.0

- Query admission releases reservations after startup failures. A dedicated scheduler renews heartbeats for active workers and CSV exports.
- Query simulations are confined to test configuration. Single-statement PostgreSQL reads using a read-only profile fetch through a JDBC cursor. Result cells fit their UTF-8 or hex byte budget and numeric sorting respects the JDBC type.
- The Explorer lists schemas first and loads tables and columns as their branches open. Object search returns bounded matches across schemas, tables and columns. Concurrent metadata loads share work per connection and profile. SQL autocomplete includes schemas, tables and columns already loaded for the active connection. The bounded cache uses literal JDBC names and is invalidated by connection changes made on that backend.
- Results require current permission to query with the profile used for execution. CSV records completion after writing the response.
- Keyed submissions keep a receipt after live execution records expire. Workbench uses the same key when retrying a lost response. Failed or canceled writes that might have reached the target show an uncertain outcome.

The optional `DWARVENPICK_DATASOURCE_MAX_POOL_CONNECTIONS_PER_TARGET` setting accounts for prepared pool capacity across profiles and connections that use the same configured hostname and port. Set the limit per backend replica and budget separately for target aliases. A pool that would exceed the limit is rejected. The default value of zero disables the limit.
