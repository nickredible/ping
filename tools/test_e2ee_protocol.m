// Verification tests & E2EE relay companion for FindMyLiveBridge.
//
// Uses ios-tweak/FMBShared.h directly, so it tests the exact code the tweak runs:
// 1. AES-256-GCM (CommonCrypto) against NIST SP 800-38D test case 16, plus tamper rejection
// 2. fmb1 envelope encryption/decryption and direction binding
// 3. Live-fix validation (rejects isMock, stale capture time, bad coordinates/accuracy)
// 4. Pairing-code parsing, including ?lan= / &token= and unsafe-character rejection
// 5. Replay protection (seq must increase and be recent)
// Optional: `--simulate-relay <fmb1_pairing_code>` reads the Android phone's uplink from the
// relay, checks it like the tweak would, and publishes an encrypted friends_state downlink.
//
// Build: clang -fobjc-arc -I ../ios-tweak -framework Foundation -framework Security \
//          test_e2ee_protocol.m -o test_e2ee_protocol

#import "FMBShared.h"
#include <stdio.h>

static int gFailures = 0;
#define CHECK(cond, label) do { \
    if (cond) { printf("  ok    %s\n", label); } \
    else { printf("  FAIL  %s\n", label); gFailures++; } \
} while (0)

static void addAuth(NSMutableURLRequest *req, NSDictionary *cfg) {
    NSString *token = cfg[@"relayToken"];
    if (token.length > 0) [req setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
}

static int simulateRelay(NSString *code) {
    NSDictionary *cfg = fmbParsePairingCode(code);
    if (!cfg) {
        fprintf(stderr, "Invalid pairing code\n");
        return 1;
    }
    NSString *relayUrl = cfg[@"relayUrl"], *chan = cfg[@"channelId"];
    NSData *kData = [[NSData alloc] initWithBase64EncodedString:cfg[@"keyBase64"] options:0];
    printf("[RelayCompanion] Polling %s/fmb-%s-up/json%s...\n", relayUrl.UTF8String, chan.UTF8String,
           cfg[@"relayToken"] ? " (with access token)" : "");

    NSString *pollUrl = [NSString stringWithFormat:@"%@/fmb-%@-up/json?poll=1&since=120s", relayUrl, chan];
    NSMutableURLRequest *pollReq = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:pollUrl]];
    addAuth(pollReq, cfg);

    __block NSData *respData = nil;
    __block NSInteger pollStatus = 0;
    dispatch_semaphore_t pollSem = dispatch_semaphore_create(0);
    [[[NSURLSession sharedSession] dataTaskWithRequest:pollReq completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) {
        respData = d;
        pollStatus = [(NSHTTPURLResponse *)r statusCode];
        dispatch_semaphore_signal(pollSem);
    }] resume];
    dispatch_semaphore_wait(pollSem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(15 * NSEC_PER_SEC)));
    printf("[RelayCompanion] Poll returned HTTP %ld\n", (long)pollStatus);

    int decryptedCount = 0;
    int64_t lastSeq = 0;
    double lastLat = 0, lastLon = 0;
    NSString *linesStr = respData ? [[NSString alloc] initWithData:respData encoding:NSUTF8StringEncoding] : @"";
    for (NSString *line in [linesStr componentsSeparatedByCharactersInSet:[NSCharacterSet newlineCharacterSet]]) {
        if (line.length < 2) continue;
        NSDictionary *outer = [NSJSONSerialization JSONObjectWithData:[line dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        if (![outer isKindOfClass:[NSDictionary class]]) continue;
        NSDictionary *innerEnv = outer;
        if ([outer[@"message"] isKindOfClass:[NSString class]]) {
            innerEnv = [NSJSONSerialization JSONObjectWithData:[outer[@"message"] dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        }
        NSDictionary *payload = decryptEnvelopeJson(innerEnv, @"up", chan, kData);
        if (!payload) continue;
        decryptedCount++;
        NSString *seqReason = @"fresh";
        bool fresh = fmbAcceptSeq(&lastSeq, payload, kMaxUplinkAgeMs, &seqReason);
        NSString *reason = @"OK";
        bool valid = [payload[@"type"] isEqualToString:@"location_update"] ? fmbValidateFixPayload(payload, &reason) : true;
        if (payload[@"lat"]) { lastLat = [payload[@"lat"] doubleValue]; lastLon = [payload[@"lon"] doubleValue]; }
        printf("[RelayCompanion] Packet #%d: type=%s hAcc=%.1fm seq=%s (%s) valid=%s (%s)\n",
               decryptedCount, [payload[@"type"] UTF8String] ?: "unknown", [payload[@"hAcc"] doubleValue],
               fresh ? "accepted" : "rejected", seqReason.UTF8String, valid ? "YES" : "NO", reason.UTF8String);
    }
    if (decryptedCount == 0) printf("[RelayCompanion] No decryptable uplink packets in the last 120 s.\n");

    int64_t downSeq = 0;
    NSDictionary *downPayload = @{
        @"type": @"friends_state",
        @"seq": @(fmbNextSeq(&downSeq)),
        @"relayTimeMs": @(currentEpochMs()),
        @"relayActive": @YES,
        @"meDeviceActive": @YES,
        @"fmfdHooked": @YES,
        @"lastInjectedFixTimeMs": @(currentEpochMs()),
        @"liveFixActive": @YES,
        @"friends": @[@{
            @"id": @"relay-test-friend",
            @"handle": @"test@example.com",
            @"name": @"Relay Test Friend",
            @"lat": @(lastLat + 0.0032),
            @"lon": @(lastLon - 0.0028),
            @"hAcc": @6.5,
            @"alt": @14.0,
            @"timestampMs": @(currentEpochMs()),
            @"address": @"Published by test_e2ee_protocol",
            @"status": @"live"
        }]
    };
    NSString *downEnv = encryptEnvelopeJson(downPayload, @"down", chan, kData);
    NSString *downUrl = [NSString stringWithFormat:@"%@/fmb-%@-down", relayUrl, chan];
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:downUrl]];
    req.HTTPMethod = @"POST";
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    addAuth(req, cfg);
    req.HTTPBody = [downEnv dataUsingEncoding:NSUTF8StringEncoding];

    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [[[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) {
        printf("[RelayCompanion] Published encrypted friends_state downlink (HTTP %ld)\n",
               (long)[(NSHTTPURLResponse *)r statusCode]);
        dispatch_semaphore_signal(sem);
    }] resume];
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(10 * NSEC_PER_SEC)));
    return 0;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        printf("AES-256-GCM (NIST SP 800-38D test case 16)\n");
        const uint8_t nistKey[32] = {
            0xfe,0xff,0xe9,0x92,0x86,0x65,0x73,0x1c,0x6d,0x6a,0x8f,0x94,0x67,0x30,0x83,0x08,
            0xfe,0xff,0xe9,0x92,0x86,0x65,0x73,0x1c,0x6d,0x6a,0x8f,0x94,0x67,0x30,0x83,0x08
        };
        const uint8_t nistIv[12] = {0xca,0xfe,0xba,0xbe,0xfa,0xce,0xdb,0xad,0xde,0xca,0xf8,0x88};
        const uint8_t nistPlain[60] = {
            0xd9,0x31,0x32,0x25,0xf8,0x84,0x06,0xe5,0xa5,0x59,0x09,0xc5,0xaf,0xf5,0x26,0x9a,
            0x86,0xa7,0xa9,0x53,0x15,0x34,0xf7,0xda,0x2e,0x4c,0x30,0x3d,0x8a,0x31,0x8a,0x72,
            0x1c,0x3c,0x0c,0x95,0x95,0x68,0x09,0x53,0x2f,0xcf,0x0e,0x24,0x49,0xa6,0xb5,0x25,
            0xb1,0x6a,0xed,0xf5,0xaa,0x0d,0xe6,0x57,0xba,0x63,0x7b,0x39
        };
        const uint8_t nistAad[20] = {
            0xfe,0xed,0xfa,0xce,0xde,0xad,0xbe,0xef,0xfe,0xed,0xfa,0xce,0xde,0xad,0xbe,0xef,
            0xab,0xad,0xda,0xd2
        };
        const uint8_t nistCt[60] = {
            0x52,0x2d,0xc1,0xf0,0x99,0x56,0x7d,0x07,0xf4,0x7f,0x37,0xa3,0x2a,0x84,0x42,0x7d,
            0x64,0x3a,0x8c,0xdc,0xbf,0xe5,0xc0,0xc9,0x75,0x98,0xa2,0xbd,0x25,0x55,0xd1,0xaa,
            0x8c,0xb0,0x8e,0x48,0x59,0x0d,0xbb,0x3d,0xa7,0xb0,0x8b,0x10,0x56,0x82,0x88,0x38,
            0xc5,0xf6,0x1e,0x63,0x93,0xba,0x7a,0x0a,0xbc,0xc9,0xf6,0x62
        };
        const uint8_t nistTag[16] = {
            0x76,0xfc,0x6e,0xce,0x0f,0x4e,0x17,0x68,0xcd,0xdf,0x88,0x53,0xbb,0x2d,0x55,0x1b
        };

        uint8_t ct[60] = {0}, tag[16] = {0}, dec[60] = {0};
        CHECK(fmb_aes256_gcm_encrypt(nistKey, nistIv, nistAad, 20, nistPlain, 60, ct, tag), "encrypt succeeds");
        CHECK(memcmp(ct, nistCt, 60) == 0, "ciphertext matches NIST");
        CHECK(memcmp(tag, nistTag, 16) == 0, "tag matches NIST");
        CHECK(fmb_aes256_gcm_decrypt(nistKey, nistIv, nistAad, 20, ct, 60, tag, dec) &&
              memcmp(dec, nistPlain, 60) == 0, "decrypt round-trips");

        uint8_t badTag[16]; memcpy(badTag, tag, 16); badTag[3] ^= 0x01;
        uint8_t badCt[60];  memcpy(badCt, ct, 60);   badCt[17] ^= 0x80;
        uint8_t badAad[20]; memcpy(badAad, nistAad, 20); badAad[0] ^= 0x01;
        CHECK(!fmb_aes256_gcm_decrypt(nistKey, nistIv, nistAad, 20, ct, 60, badTag, dec), "tampered tag rejected");
        CHECK(!fmb_aes256_gcm_decrypt(nistKey, nistIv, nistAad, 20, badCt, 60, tag, dec), "tampered ciphertext rejected");
        CHECK(!fmb_aes256_gcm_decrypt(nistKey, nistIv, badAad, 20, ct, 60, tag, dec), "tampered AAD rejected");

        printf("Envelope\n");
        NSData *keyData = [NSData dataWithBytes:nistKey length:32];
        NSString *channelId = @"a1b2c3d4e5f60718293a4b5c";
        int64_t now = currentEpochMs();
        NSDictionary *liveFix = @{
            @"type": @"location_update", @"seq": @(now),
            @"lat": @51.5007, @"lon": @(-0.1246), @"hAcc": @4.8,
            @"fixTimeMs": @(now - 3000), @"isMock": @NO
        };
        NSString *envJson = encryptEnvelopeJson(liveFix, @"up", channelId, keyData);
        CHECK(envJson != nil, "encrypts payload");
        NSDictionary *envDict = [NSJSONSerialization JSONObjectWithData:[envJson dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        CHECK(decryptEnvelopeJson(envDict, @"down", channelId, keyData) == nil, "wrong direction rejected");
        CHECK(decryptEnvelopeJson(envDict, @"up", @"ffffffffffffffffffffffff", keyData) == nil, "wrong channel rejected");
        NSMutableDictionary *flipped = [envDict mutableCopy];
        flipped[@"dir"] = @"down";
        CHECK(decryptEnvelopeJson(flipped, @"down", channelId, keyData) == nil, "relabelled direction rejected (AAD)");
        NSDictionary *unwrapped = decryptEnvelopeJson(envDict, @"up", channelId, keyData);
        CHECK([unwrapped[@"lat"] doubleValue] == 51.5007, "decrypts payload");

        printf("Live-fix validation\n");
        NSString *reason = nil;
        CHECK(fmbValidateFixPayload(unwrapped, &reason), "fresh fix accepted");
        NSMutableDictionary *fix = [liveFix mutableCopy];
        fix[@"isMock"] = @YES;
        CHECK(!fmbValidateFixPayload(fix, &reason) && [reason containsString:@"mock"], "isMock rejected");
        fix = [liveFix mutableCopy];
        fix[@"fixTimeMs"] = @(now - 300000);
        CHECK(!fmbValidateFixPayload(fix, &reason) && [reason containsString:@"not live"], "5-minute-old capture rejected");
        fix = [liveFix mutableCopy];
        fix[@"hAcc"] = @2500.0;
        CHECK(!fmbValidateFixPayload(fix, &reason), "2500 m accuracy rejected");
        fix = [liveFix mutableCopy];
        fix[@"lat"] = @0.0; fix[@"lon"] = @0.0;
        CHECK(!fmbValidateFixPayload(fix, &reason), "null island rejected");

        printf("Pairing codes\n");
        NSString *keyB64 = [keyData base64EncodedStringWithOptions:0];
        NSString *plain = fmbBuildPairingCode(@"https://ntfy.example.com", channelId, keyB64, nil, nil);
        NSDictionary *p1 = fmbParsePairingCode(plain);
        CHECK([p1[@"relayUrl"] isEqualToString:@"https://ntfy.example.com"] && [p1[@"channelId"] isEqualToString:channelId] &&
              [p1[@"keyBase64"] isEqualToString:keyB64] && !p1[@"lanEndpoint"] && !p1[@"relayToken"], "plain code parses");
        NSString *full = fmbBuildPairingCode(@"https://ntfy.example.com", channelId, keyB64, @"192.168.1.50:8765", @"tk_abc123XYZ");
        NSDictionary *p2 = fmbParsePairingCode(full);
        CHECK([p2[@"lanEndpoint"] isEqualToString:@"192.168.1.50:8765"] && [p2[@"relayToken"] isEqualToString:@"tk_abc123XYZ"],
              "lan + token parse");
        NSString *tokenOnly = fmbBuildPairingCode(@"https://ntfy.example.com", channelId, keyB64, nil, @"tk_abc");
        CHECK([fmbParsePairingCode(tokenOnly)[@"relayToken"] isEqualToString:@"tk_abc"] && !fmbParsePairingCode(tokenOnly)[@"lanEndpoint"],
              "token without lan parses");
        NSString *legacy = [NSString stringWithFormat:@"fmb1:https://ntfy.sh:%@:%@?lan=100.64.0.5:8765", channelId, keyB64];
        CHECK([fmbParsePairingCode(legacy)[@"lanEndpoint"] isEqualToString:@"100.64.0.5:8765"], "old ?lan= codes still parse");
        NSString *injected = [full stringByAppendingString:@"%0d%0aX-Evil:1"];
        CHECK(fmbParsePairingCode(injected) == nil, "token with unsafe characters rejected");
        CHECK(fmbParsePairingCode(@"fmb1:https://ntfy.sh:short:AAAA") == nil, "short channel/key rejected");

        printf("Replay protection\n");
        int64_t lastAccepted = 0;
        NSDictionary *m1 = @{@"seq": @(now)};
        NSDictionary *m2 = @{@"seq": @(now + 1)};
        CHECK(fmbAcceptSeq(&lastAccepted, m1, kMaxUplinkAgeMs, &reason), "first message accepted");
        CHECK(!fmbAcceptSeq(&lastAccepted, m1, kMaxUplinkAgeMs, &reason), "exact replay rejected");
        CHECK(fmbAcceptSeq(&lastAccepted, m2, kMaxUplinkAgeMs, &reason), "newer message accepted");
        CHECK(!fmbAcceptSeq(&lastAccepted, m1, kMaxUplinkAgeMs, &reason), "older message rejected");
        int64_t freshReceiver = 0; // e.g. after a respring
        CHECK(!fmbAcceptSeq(&freshReceiver, @{@"seq": @(now - 600000)}, kMaxUplinkAgeMs, &reason),
              "10-minute-old message rejected after restart");
        CHECK(!fmbAcceptSeq(&freshReceiver, @{@"type": @"location_update"}, kMaxUplinkAgeMs, &reason), "missing seq rejected");
        int64_t senderSeq = 0;
        int64_t s1 = fmbNextSeq(&senderSeq), s2 = fmbNextSeq(&senderSeq), s3 = fmbNextSeq(&senderSeq);
        CHECK(s1 < s2 && s2 < s3, "sender seq strictly increases within the same millisecond");

        if (gFailures > 0) {
            printf("[FAIL] %d check(s) failed.\n", gFailures);
            return 1;
        }
        printf("[PASS] All checks passed.\n");

        if (argc >= 3 && strcmp(argv[1], "--simulate-relay") == 0) {
            return simulateRelay([NSString stringWithUTF8String:argv[2]]);
        }
        return 0;
    }
}
