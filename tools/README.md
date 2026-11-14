# Tools

## `test_e2ee_protocol.m`

Cross-checks the shared protocol code the tweak actually uses
([`../ios-tweak/FMBShared.h`](../ios-tweak/FMBShared.h)) and, by extension, the Android
implementation it mirrors. It verifies:

- AES-256-GCM against the NIST SP 800-38D test vector, plus tamper rejection (bad tag / ciphertext /
  AAD);
- `fmb1` envelope encryption, decryption and direction binding;
- live-fix validation (rejects `isMock`, stale capture time, bad coordinates/accuracy);
- pairing-code parsing, including `?lan=` / `&token=`, LAN/VPN-only (`none`) codes and rejection of
  unsafe characters;
- replay protection (`seq` must increase and be recent).

Build and run:

```bash
clang -fobjc-arc -I ../ios-tweak -framework Foundation -framework Security \
  test_e2ee_protocol.m -o test_e2ee_protocol
./test_e2ee_protocol
```

With a pairing code it can also act as a stand-in relay peer for manual testing:

```bash
./test_e2ee_protocol --simulate-relay 'fmb1:…'
```

The compiled binary is gitignored.
