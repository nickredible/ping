# Ping

**Your Android phone, live on Apple's Find My**, relayed through a spare jailbroken iPhone.

> [!CAUTION]
> - **100% vibe-coded** and not audited.
> - **Using this may be against Apple's terms of service.** You take full responsibility for
>   anything that happens to your Apple Account or devices.
> - **OpenBubbles users must disable `identityservicesd`** on the relay iPhone, or it interferes
>   with the OpenBubbles relay ([step 6](#6-openbubbles-users-disable-identityservicesd)).

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

**Direct LAN/VPN link (optional).** If both phones share a network or VPN (e.g. Tailscale or NetBird),
Ping also posts straight to the iPhone on TCP 8765. The tweak puts its own address (preferring a
`100.x` VPN address) in the pairing code, and you can change it in Ping under **Settings → Direct
LAN/VPN address**. You still need a relay, because the iPhone uses it for every push it starts.

### 2. Install the tweak

**Pre-built:** download `com.findmy.livebridge_<version>_iphoneos-arm64.deb` from
[Releases](https://github.com/nickredible/ping/releases).

**Or build it** on a Mac with Xcode command-line tools: `cd ios-tweak && python3 build_deb.py`
(it writes to `ios-tweak/build/`).

Install it as root, then respring:

```bash
scp com.findmy.livebridge_*_iphoneos-arm64.deb root@<iphone>:/tmp/
```

```bash
ssh root@<iphone> 'dpkg -i /tmp/com.findmy.livebridge_*_iphoneos-arm64.deb'
```

More detail: [`ios-tweak/README.md`](ios-tweak/README.md).

### 3. Configure the iPhone

1. **Settings → [your name] → Find My → Use This iPhone as My Location.**
2. **Settings → Ping Relay:** set **Internet Relay Server** to your ntfy URL (this creates a new
   key), then **Relay Access Token** to your `tk_…` token.
3. Open **Find My → People** once, so the tweak learns your friends' IDs and names.

The **Active Pairing Code** (`fmb1:…`) now holds your relay, token and encryption key. **Treat it
like a password.**

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

### 6. OpenBubbles users: disable `identityservicesd`

**If you also use [OpenBubbles](https://openbubbles.app) with the same Apple Account, this step is
required.** Without it, Apple removes your phone number from the account shortly after every
OpenBubbles sign-in and OpenBubbles gets logged out with error 6005.

On the relay iPhone, as root:

```bash
launchctl unload -w /System/Library/LaunchDaemons/com.apple.identityservicesd.plist
```

The `-w` keeps it off across reboots. **iMessage and FaceTime stop working on the relay iPhone**,
which is fine for a phone that only relays. To undo it:

```bash
launchctl load -w /System/Library/LaunchDaemons/com.apple.identityservicesd.plist
```

## Good to know

- Sharing runs as a foreground service. After a reboot, open Ping once to start it again.
- When you're still, Ping goes near-idle and sends heartbeats. If it stops reporting (no signal, phone
  off), Find My keeps showing its last position until it reports again, instead of jumping to the iPhone.
- Anyone holding the pairing code can set your Find My location. If it leaks, generate a new key and
  re-pair. See [`docs/SECURITY.md`](docs/SECURITY.md).

| Problem | Fix |
|---|---|
| Bridge Status stuck on *Waiting for Android GPS* | Re-paste the code, check the token, and make sure your proxy doesn't buffer streams |
| `Relay rate limit (429)` in Ping | Self-host ntfy |
| No friends in Ping | Open Find My → People on the iPhone once, then refresh in Ping |
| Me tab says *Not set as the My Location device* | Use This iPhone as My Location (step 3) |
| OpenBubbles keeps logging out (6005) | Step 6 |

## Docs

[Architecture](docs/ARCHITECTURE.md) · [Protocol](docs/PROTOCOL.md) ·
[Security](docs/SECURITY.md) · [Relay](docs/RELAY.md) · [Android app](android/README.md) ·
[iOS tweak](ios-tweak/README.md) · [Test tool](tools/README.md)
