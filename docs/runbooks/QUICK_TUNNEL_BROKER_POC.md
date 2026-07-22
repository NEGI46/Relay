# Quick Tunnel Broker PoC

This Docker Compose configuration is for a time-limited development test only. The Broker never
decrypts rescue envelopes, but Cloudflare Quick Tunnel has no SLA and is not for production.

## Start the Broker and tunnel

```powershell
.\gradlew.bat :broker:installDist
docker compose -f compose.quick-tunnel.yml up -d --build
docker compose -f compose.quick-tunnel.yml logs -f cloudflared
```

Copy the random `https://*.trycloudflare.com` URL from the `cloudflared` log. Verify it without
revealing any credential:

```powershell
Invoke-RestMethod https://<random>.trycloudflare.com/v1/health
```

## Attach the PC Gateway

The supplied launcher generates a per-Gateway/per-shelter credential and keeps its one-time value
only in the current PowerShell process. It also discovers the current Quick Tunnel URL. For the
initial Android key enrollment only, enable the trusted private-LAN listener:

```powershell
.\scripts\start-poc-broker-gateway.ps1 -EnableLanEnrollment
```

The Gateway logs `Broker cloud relay: ...` when its outbound pull agent is connected. Test from
the PC with `Invoke-RestMethod $env:RELAY_BROKER_URL/v1/health`; aggregate queue counts only are
exposed.

Before opening the local operator console for the first time, stop the temporary Gateway process,
then initialize its one-time local administrator. The script prompts for the username and password
without placing the password in command history or output.

```powershell
.\scripts\initialize-poc-gateway-admin.ps1
```

If the Gateway is using its standard user-profile database (for example, the local Gateway at
port 8080), target that exact database explicitly:

```powershell
.\scripts\initialize-poc-gateway-admin.ps1 -StateRoot "$env:USERPROFILE\.relay" -DatabasePath "$env:USERPROFILE\.relay\relay-gateway.db"
```

Restart the Gateway with the same PoC environment afterwards and sign in at
`http://127.0.0.1:8080/`.

## Build the Android test APK

```powershell
.\gradlew.bat :app:assembleDebug -Prelay.broker.endpoint=https://<random>.trycloudflare.com
```

Install `app/build/outputs/apk/debug/app-debug.apk` on the Android phone by any normal distribution
method, then disconnect Wi-Fi so the app uses mobile data. A valid request returns `BROKER_STORED` first; it is not a
shelter-accepted receipt. The PC Gateway's operator UI confirms the later shelter intake.

Stop and remove the prototype data when the exercise ends:

```powershell
docker compose -f compose.quick-tunnel.yml down -v
```
