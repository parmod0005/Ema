# VARDHANI AI Bridge deployment

The bridge is paper-only. It does not contain any Upstox order endpoint.

## Required secrets

Set these only on the HTTPS server:

- `OPENAI_API_KEY`
- `VARDHANI_DEVICE_TOKEN` (use a long random value)
- `OPENAI_MODEL`

Do not commit real values and do not place the OpenAI key in the Android app.

## Render blueprint

The repository root contains `render.yaml`. Create a new Render Blueprint from this repository and branch `develop/ema-android-foundation`. Add `OPENAI_API_KEY` in the service environment. Render generates `VARDHANI_DEVICE_TOKEN`; copy its value securely for the Android app.

The service must expose an HTTPS URL such as `https://<service>.onrender.com`.

## Verify the deployment

Run:

```bash
python bridge/smoke_test.py https://<bridge-host> '<device-token>'
```

A valid deployment must report `paperOnly: true`, then return a strict `BUY_CE`, `BUY_PE`, or `WAIT` decision whose `snapshotId` matches the request.

## Configure VARDHANI

In the Android AI panel:

1. Enter the HTTPS bridge base URL without `/v1/analyze`.
2. Enter the same device token stored on the server.
3. Select **AI Brain** or **Hybrid**.
4. Keep **Shadow** selected for the first live session.
5. Confirm bridge health and latency before relying on any displayed signal.

Do not enable AI Paper mode until Shadow-mode snapshots and decisions have been audited for a full market session. Live broker execution remains disabled in the current app.

## Production checks

- TLS certificate valid
- `/v1/health` reachable
- audit disk mounted and writable
- API key and device token stored as secrets
- rate limit enabled
- server clock synchronized
- no credentials visible in logs
- Android rejects stale or mismatched decisions
