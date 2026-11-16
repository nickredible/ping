# Ping

**Your Android phone, live on Apple's Find My**, relayed through a spare jailbroken iPhone.

> [!CAUTION]
> - **100% vibe-coded** and not audited.
> - **Using this may be against Apple's terms of service.** You take full responsibility for
>   anything that happens to your Apple Account or devices.
> - **OpenBubbles users will likely have to re-register their phone number** during setup. Follow
>   [step 3](#3-configure-the-iphone) carefully to avoid it being removed again later.

> [!NOTE]
> Not affiliated with Apple or OpenBubbles. All names and trademarks belong to their respective holders.

## What it does

Apple only lets an iPhone or iPad be your **Share My Location** device. Ping gets around that:

- **You → Find My.** Ping sends your Android phone's GPS to a jailbroken iPhone signed into your
  Apple Account. A tweak there makes Find My publish it as the iPhone's own location.
- **Friends → you.** The tweak captures your friends' locations as the iPhone decrypts them and
  sends them back to Ping, so you get a Find My friends map on Android.

Both directions are end-to-end encrypted (AES-256-GCM), and the relay in between only sees
ciphertext. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how it works.

| Component | What it is | Where |
|---|---|---|
| **Ping** | Android app, Android 12+ | [`android/`](android/) |
| **Ping Relay** | Jailbreak tweak for iOS 15 (Dopamine rootless + ElleKit), tested on **15.4.1** | [`ios-tweak/`](ios-tweak/) |
| **Test tool** | Checks the shared crypto/protocol code | [`tools/`](tools/) |

The tweak has only been tested on iOS **15.4.1**. It will likely work on other iOS 15 releases, but
they haven't been tested, and neither has anything newer. The hooks depend on private Find My
internals that can change between versions.

## What you need

- A spare **iPhone on iOS 15** (tested on 15.4.1), jailbroken with **Dopamine**, with **ElleKit**,
  **PreferenceLoader** and OpenSSH installed. It must be signed into your Apple Account with Find My
  on, and stay at home on power.
- An **Android phone** on Android 12 or newer.
- Ideally, a **server for ntfy** (Docker), reachable over HTTPS from both phones.

## Setup

### 1. Set up a relay

The phones talk through an [ntfy](https://ntfy.sh) server. **Self-hosting one is highly
recommended.** The public `ntfy.sh` works out of the box, but:

- **It's rate-limited.** Anonymous clients run into HTTP 429s, especially with the app open.
- **It isn't private.** It can't read your locations, but its operator sees your topic names,
  message timing and both phones' IP addresses.

To self-host, use [`ntfy/compose.yml`](ntfy/compose.yml) and [`ntfy/server.yml`](ntfy/server.yml):
set `base-url`, run `docker compose up -d`, create the token (commands are at the top of
`compose.yml`), and put it behind HTTPS with response buffering off. Keep the URL and the `tk_…`
token for step 3.

**Or skip the relay with a VPN.** If both phones are always on the same VPN (e.g. Tailscale, which
supports iOS 15), they can talk directly on TCP 8765 with no relay at all. On the iPhone, choose
**Settings → Ping Relay → Internet Relay Server → No Relay (LAN/VPN Only)**, and pair as usual. The
code then carries the iPhone's VPN address. The catch is that Ping only works while both phones are
connected to the VPN. Details: [`docs/RELAY.md`](docs/RELAY.md#3-direct-lan--vpn).

With a relay set, Ping still tries the LAN/VPN address first when it can reach it.

### 2. Install the tweak

**From Sileo (recommended):** add the source `https://nickredible.github.io/ping/`
([one tap](https://nickredible.github.io/ping/) on the iPhone), install **Ping Relay**, then
respring. Updates arrive through Sileo like any other tweak.

**Or the `.deb`:** download `uk.maynotfunction.pingrelay_<version>_iphoneos-arm64.deb` from
[Releases](https://github.com/nickredible/ping/releases), or build it on a Mac with Xcode
command-line tools (`cd ios-tweak && python3 build_deb.py`, which writes to `ios-tweak/build/`).
Install it as root, then respring:

```bash
scp uk.maynotfunction.pingrelay_*_iphoneos-arm64.deb root@<iphone>:/tmp/
```

```bash
ssh root@<iphone> 'dpkg -i /tmp/uk.maynotfunction.pingrelay_*_iphoneos-arm64.deb'
```

More detail: [`ios-tweak/README.md`](ios-tweak/README.md).

### 3. Configure the iPhone

1. **Sign in to iCloud** with your Apple Account (**Settings → Sign in to your iPhone**). Leave
   `identityservicesd` running for now: your friends' phones send their location keys to a newly
   signed-in iPhone over it, and without them Find My shows *No location found*.
   - **OpenBubbles users:** make sure **iMessage** (Settings → Messages) and **FaceTime**
     (Settings → FaceTime) are off. To be safe, also turn off everything under
     **Settings → [your name] → iCloud**. Leave Find My on.
   - **Already signed in to OpenBubbles?** Expect it to log out (error 6005) while
     `identityservicesd` runs, because Apple removes your phone number. You sign back in at step 6.
2. **Settings → [your name] → Find My → Use This iPhone as My Location.**
3. **Settings → Ping Relay:** set **Internet Relay Server** to your ntfy URL (this creates a new
   key), then **Relay Access Token** to your `tk_…` token.
4. Open **Find My → People** and **wait until every friend shows a location.** This can take a
   while. If someone stays on *No location found*, ask them to stop sharing with you and share again.
5. **OpenBubbles users: now disable `identityservicesd`.** As root:

   ```bash
   launchctl unload -w /System/Library/LaunchDaemons/com.apple.identityservicesd.plist
   ```

   The `-w` keeps it off across reboots. Friends keep updating without it. To undo it, run
   `launchctl load -w` with the same path.
6. **OpenBubbles users:** sign in to OpenBubbles (again, if it logged out).

The **Active Pairing Code** (`fmb1:…`) now holds your relay, token and encryption key. **Treat it
like a password.**

**Don't sign the relay out of iCloud later.** Signing back in makes it a new device to your friends'
phones, so you'd have to repeat steps 1–6, including the OpenBubbles logout.

### 4. Install Ping on Android

**Pre-built:** download the `.apk` from [Releases](https://github.com/nickredible/ping/releases) and
install it.

**Or build it** with JDK 21 and the Android SDK: `cd android && ./gradlew :app:assembleRelease`.
Signing and the optional Google Maps key are covered in [`android/README.md`](android/README.md).

### 5. Run Ping's setup

Open Ping. Setup walks you through permissions, pairing, a connection check and battery settings.
To pair, copy the code on the iPhone (**Settings → Ping Relay → Copy Pairing Code**) and move it to
the Android phone over a channel you trust, since it contains the encryption key. You can also read it
over SSH without it showing on screen:

```bash
ssh root@<iphone> curl -s http://127.0.0.1:8765/v1/pairing
```

Afterwards the iPhone's **Bridge Status** says *Active*. To redo setup, go to **Settings → Run setup
again**.

## Good to know

- Sharing runs as a foreground service. It restarts by itself after a reboot if location is allowed
  **all the time** (setup asks, or **Settings → Restart after reboot**); otherwise open Ping once.
- When a friend opens your location in Find My, Ping streams it live, like an iPhone, until Find My
  stops (a few minutes after they leave).
- If the relay iPhone stops answering for 30 minutes (off, offline, or the jailbreak lost after a
  restart), Ping notifies you.
- When you're still, Ping goes near-idle and sends heartbeats. If it stops reporting (no signal, phone
  off), Find My keeps showing its last position until it reports again, instead of jumping to the iPhone.
- Anyone holding the pairing code can set your Find My location. If it leaks, generate a new key and
  re-pair. See [`docs/SECURITY.md`](docs/SECURITY.md).

| Problem | Fix |
|---|---|
| Bridge Status stuck on *Waiting for Android GPS* | Re-paste the code, check the token, and make sure your proxy doesn't buffer streams |
| `Relay rate limit (429)` in Ping | Self-host ntfy |
| No friends in Ping | Open Find My → People on the iPhone once, then refresh in Ping |
| A friend shows *No location found* on the iPhone | Their key never reached the relay. Turn `identityservicesd` on until they appear (OpenBubbles users lose OpenBubbles meanwhile), or ask them to share again |
| Me tab says *Not set as the My Location device* | Use This iPhone as My Location (step 3) |
| *Relay iPhone isn't responding* notification | Check the iPhone is on and online. If it restarted, re-jailbreak it with Dopamine |
| OpenBubbles keeps logging out (6005) | Disable `identityservicesd` once friends have synced (step 3) |

## Docs

[Architecture](docs/ARCHITECTURE.md) · [Protocol](docs/PROTOCOL.md) ·
[Security](docs/SECURITY.md) · [Relay](docs/RELAY.md) · [Android app](android/README.md) ·
[iOS tweak](ios-tweak/README.md) · [Test tool](tools/README.md)

## License

[MIT](LICENSE). Bundled third-party fonts and libraries keep their own licenses, listed in Ping under
**Settings → Open source licenses**.
