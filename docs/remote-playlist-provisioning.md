# Remote playlist provisioning

## Current release boundary

Crown Media currently stores manually entered Xtream credentials in `CrownSecureStore`. The
activation base URL and QR entry point are deliberately disabled because no authenticated
activation service contract exists yet. Enabling the existing placeholder would expose a stable
device identifier without providing a secure way to claim, update, rotate, or revoke a playlist.

Manual playlist login remains the supported release path until the service below is available.

## Recommended flow

1. On first activation, the app creates a non-exportable Android Keystore signing key and requests
   a short-lived pairing session from the Crown activation API.
2. The API returns a random, single-use pairing code and QR payload with a maximum 10-minute TTL.
   The payload contains a session identifier and proof challenge, never Xtream credentials.
3. An authenticated provider administrator claims that session in a tenant-scoped web portal and
   assigns a playlist configuration.
4. The TV polls the pairing session using a signed nonce. After approval, it exchanges the
   one-time grant for a device-scoped refresh token and downloads an encrypted configuration.
5. The app validates the playlist against the Xtream endpoint, stores credentials only through
   `CrownSecureStore`, records the configuration version, and acknowledges activation.
6. A manual **Sync playlist** action and a constrained WorkManager job check an ETag/version for
   later updates. Failed updates retain the last valid configuration.

Do not use a MAC address, serial number, or raw Android ID as authentication. They are unreliable
across Fire TV/Android TV devices, may be unavailable, and are not secrets.

## Minimum API contract

- `POST /v1/pairing-sessions` — issue a random code, QR challenge, and expiry.
- `GET /v1/pairing-sessions/{id}` — signed polling; returns pending, approved, expired, or revoked.
- `POST /v1/device-token` — one-time grant exchange bound to the installation public key.
- `GET /v1/device/configuration` — authenticated, versioned configuration response.
- `POST /v1/device/configuration/ack` — validation result without returning plaintext secrets.
- `DELETE /v1/devices/{id}` — administrator revocation and token invalidation.

Every operation must enforce tenant ownership, rate limits, expiry, replay protection, audit logs,
and TLS. Provider credentials should be encrypted at rest with a managed KMS and returned only to
the assigned device. Logs, analytics, QR payloads, push notifications, and crash reports must never
contain the server password, username, or full playlist URL.

## Client implementation gate

Implement and expose remote activation only after:

- the API schema and authentication model are approved;
- a provider-admin portal can claim, update, and revoke a device;
- certificate/TLS policy, token rotation, account separation, and incident revocation are tested;
- retry, offline, expired-code, invalid-credential, rollback, and reinstall flows have integration
  tests against a staging service;
- manual login remains available as a fallback.

The existing static device-key/QR placeholder should be replaced by the short-lived session flow,
not extended into a provisioning mechanism.
