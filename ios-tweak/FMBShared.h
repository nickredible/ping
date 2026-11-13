// FMBShared.h - protocol code shared by Tweak.m and tools/test_e2ee_protocol.m.
//
// Everything here is pure (no files, no hooks), so the test tool exercises exactly
// the code the tweak runs: AES-256-GCM via Apple's CommonCrypto, the fmb1 envelope,
// pairing-code parsing, live-fix validation and replay protection.

#ifndef FMB_SHARED_H
#define FMB_SHARED_H

#import <Foundation/Foundation.h>
#import <CommonCrypto/CommonCryptor.h>
#import <Security/Security.h>

#pragma mark - Protocol constants

static const int64_t kMaxCaptureSkewMs = 120 * 1000;  // fix capture time vs. now
static const double  kMaxHorizontalAcc = 2000.0;      // metres
static const int64_t kMaxUplinkAgeMs   = 120 * 1000;  // message seq vs. now (replay window)

static inline int64_t currentEpochMs(void) {
    return (int64_t)([[NSDate date] timeIntervalSince1970] * 1000.0);
}

#pragma mark - AES-256-GCM (CommonCrypto)

// Exported by libcommonCrypto (via libSystem) on iOS 11+ / macOS 10.13+, but declared
// only in the private CommonCryptorSPI.h, so declare them here. Decrypt compares the
// tag in constant time and fails with a non-success status on mismatch.
extern CCCryptorStatus CCCryptorGCMOneshotEncrypt(CCAlgorithm alg, const void *key, size_t keyLength,
                                                  const void *iv, size_t ivLength,
                                                  const void *aData, size_t aDataLength,
                                                  const void *dataIn, size_t dataInLength,
                                                  void *dataOut, void *tagOut, size_t tagLength);
extern CCCryptorStatus CCCryptorGCMOneshotDecrypt(CCAlgorithm alg, const void *key, size_t keyLength,
                                                  const void *iv, size_t ivLen,
                                                  const void *aData, size_t aDataLen,
                                                  const void *dataIn, size_t dataInLength,
                                                  void *dataOut, const void *tagIn, size_t tagLength);

static inline bool fmb_aes256_gcm_encrypt(const uint8_t key[32], const uint8_t iv[12],
                                          const uint8_t *aad, size_t aadLen,
                                          const uint8_t *plaintext, size_t plaintextLen,
                                          uint8_t *ciphertextOut, uint8_t tagOut[16]) {
    return CCCryptorGCMOneshotEncrypt(kCCAlgorithmAES, key, 32, iv, 12, aad, aadLen,
                                      plaintext, plaintextLen, ciphertextOut, tagOut, 16) == kCCSuccess;
}

static inline bool fmb_aes256_gcm_decrypt(const uint8_t key[32], const uint8_t iv[12],
                                          const uint8_t *aad, size_t aadLen,
                                          const uint8_t *ciphertext, size_t ciphertextLen,
                                          const uint8_t expectedTag[16], uint8_t *plaintextOut) {
    return CCCryptorGCMOneshotDecrypt(kCCAlgorithmAES, key, 32, iv, 12, aad, aadLen,
                                      ciphertext, ciphertextLen, plaintextOut, expectedTag, 16) == kCCSuccess;
}

#pragma mark - fmb1 envelope

static inline NSString *encryptEnvelopeJson(NSDictionary *payloadDict, NSString *direction, NSString *channelId, NSData *keyData) {
    if (!payloadDict || keyData.length != 32 || channelId.length == 0) return nil;
    NSError *err = nil;
    NSData *plainData = [NSJSONSerialization dataWithJSONObject:payloadDict options:0 error:&err];
    if (!plainData || err) return nil;

    uint8_t iv[12];
    if (SecRandomCopyBytes(kSecRandomDefault, sizeof(iv), iv) != errSecSuccess) return nil;

    NSData *aadData = [[NSString stringWithFormat:@"fmb1:%@:%@", direction, channelId] dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *cipherData = [NSMutableData dataWithLength:plainData.length];
    uint8_t tag[16];
    if (!fmb_aes256_gcm_encrypt((const uint8_t *)keyData.bytes, iv,
                                (const uint8_t *)aadData.bytes, aadData.length,
                                (const uint8_t *)plainData.bytes, plainData.length,
                                (uint8_t *)cipherData.mutableBytes, tag)) {
        return nil;
    }

    NSDictionary *env = @{
        @"v": @1,
        @"dir": direction,
        @"ts": @(currentEpochMs()),
        @"iv": [[NSData dataWithBytes:iv length:12] base64EncodedStringWithOptions:0],
        @"ct": [cipherData base64EncodedStringWithOptions:0],
        @"tag": [[NSData dataWithBytes:tag length:16] base64EncodedStringWithOptions:0]
    };
    NSData *envData = [NSJSONSerialization dataWithJSONObject:env options:0 error:&err];
    if (!envData || err) return nil;
    return [[NSString alloc] initWithData:envData encoding:NSUTF8StringEncoding];
}

static inline NSDictionary *decryptEnvelopeJson(NSDictionary *envDict, NSString *expectedDirection, NSString *channelId, NSData *keyData) {
    if (![envDict isKindOfClass:[NSDictionary class]] || keyData.length != 32) return nil;
    NSString *dir = envDict[@"dir"];
    if (![dir isKindOfClass:[NSString class]] || ![dir isEqualToString:expectedDirection]) return nil;

    NSString *ivB64 = envDict[@"iv"], *ctB64 = envDict[@"ct"], *tagB64 = envDict[@"tag"];
    if (![ivB64 isKindOfClass:[NSString class]] ||
        ![ctB64 isKindOfClass:[NSString class]] ||
        ![tagB64 isKindOfClass:[NSString class]]) {
        return nil;
    }

    NSData *ivData  = [[NSData alloc] initWithBase64EncodedString:ivB64 options:0];
    NSData *ctData  = [[NSData alloc] initWithBase64EncodedString:ctB64 options:0];
    NSData *tagData = [[NSData alloc] initWithBase64EncodedString:tagB64 options:0];
    if (ivData.length != 12 || tagData.length != 16 || !ctData) return nil;

    NSData *aadData = [[NSString stringWithFormat:@"fmb1:%@:%@", expectedDirection, channelId] dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *plainData = [NSMutableData dataWithLength:ctData.length];
    if (!fmb_aes256_gcm_decrypt((const uint8_t *)keyData.bytes, (const uint8_t *)ivData.bytes,
                                (const uint8_t *)aadData.bytes, aadData.length,
                                (const uint8_t *)ctData.bytes, ctData.length,
                                (const uint8_t *)tagData.bytes, (uint8_t *)plainData.mutableBytes)) {
        return nil;
    }

    NSDictionary *decoded = [NSJSONSerialization JSONObjectWithData:plainData options:0 error:nil];
    return [decoded isKindOfClass:[NSDictionary class]] ? decoded : nil;
}

#pragma mark - Pairing code

// fmb1:<relayUrl>:<channelId>:<base64Key>[?lan=<host:port>][&token=<relay access token>]
// Only conservative characters are accepted for lan/token, since they end up in URLs and headers.
static inline BOOL fmbIsSafeParam(NSString *s, NSString *extraAllowed) {
    if (s.length == 0 || s.length > 200) return NO;
    NSMutableCharacterSet *ok = [NSMutableCharacterSet alphanumericCharacterSet];
    [ok addCharactersInString:extraAllowed];
    return [s rangeOfCharacterFromSet:[ok invertedSet]].location == NSNotFound;
}

static inline NSString *fmbBuildPairingCode(NSString *relayUrl, NSString *channelId, NSString *keyB64,
                                            NSString *lanEndpoint, NSString *relayToken) {
    NSMutableString *code = [NSMutableString stringWithFormat:@"fmb1:%@:%@:%@", relayUrl, channelId, keyB64];
    NSString *sep = @"?";
    if (lanEndpoint.length > 0) { [code appendFormat:@"%@lan=%@", sep, lanEndpoint]; sep = @"&"; }
    if (relayToken.length > 0)  { [code appendFormat:@"%@token=%@", sep, relayToken]; }
    return code;
}

// Returns relayUrl, channelId, keyBase64, pairingCode and optionally lanEndpoint / relayToken.
static inline NSDictionary *fmbParsePairingCode(NSString *code) {
    if (![code isKindOfClass:[NSString class]]) return nil;
    NSString *trimmed = [code stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (![trimmed hasPrefix:@"fmb1:"]) return nil;

    NSString *body = [trimmed substringFromIndex:5];
    NSString *lanEndpoint = nil, *relayToken = nil;
    NSRange q = [body rangeOfString:@"?"];
    if (q.location != NSNotFound) {
        NSString *query = [body substringFromIndex:q.location + 1];
        body = [body substringToIndex:q.location];
        for (NSString *pair in [query componentsSeparatedByString:@"&"]) {
            NSRange eq = [pair rangeOfString:@"="];
            if (eq.location == NSNotFound) continue;
            NSString *k = [pair substringToIndex:eq.location];
            NSString *v = [[pair substringFromIndex:eq.location + 1]
                           stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
            if ([k isEqualToString:@"lan"] && v.length > 0) {
                if (!fmbIsSafeParam(v, @".:-[]")) return nil;
                lanEndpoint = v;
            } else if ([k isEqualToString:@"token"] && v.length > 0) {
                if (!fmbIsSafeParam(v, @"._-")) return nil;
                relayToken = v;
            }
        }
    }

    NSRange lastColon = [body rangeOfString:@":" options:NSBackwardsSearch];
    if (lastColon.location == NSNotFound) return nil;
    NSString *keyB64 = [body substringFromIndex:lastColon.location + 1];
    NSString *prefix = [body substringToIndex:lastColon.location];

    NSRange prevColon = [prefix rangeOfString:@":" options:NSBackwardsSearch];
    if (prevColon.location == NSNotFound) return nil;
    NSString *channelId = [prefix substringFromIndex:prevColon.location + 1];
    NSString *relayUrl  = [prefix substringToIndex:prevColon.location];

    NSString *stdB64 = [[keyB64 stringByReplacingOccurrencesOfString:@"-" withString:@"+"]
                        stringByReplacingOccurrencesOfString:@"_" withString:@"/"];
    while (stdB64.length % 4 != 0) stdB64 = [stdB64 stringByAppendingString:@"="];
    NSData *keyData = [[NSData alloc] initWithBase64EncodedString:stdB64 options:0];
    if (keyData.length != 32 || channelId.length < 16 || relayUrl.length < 8) return nil;

    NSMutableDictionary *cfg = [@{
        @"relayUrl": relayUrl,
        @"channelId": channelId,
        @"keyBase64": [keyData base64EncodedStringWithOptions:0],
        @"pairingCode": trimmed
    } mutableCopy];
    if (lanEndpoint) cfg[@"lanEndpoint"] = lanEndpoint;
    if (relayToken)  cfg[@"relayToken"] = relayToken;
    return cfg;
}

#pragma mark - Live fix validation

// Checks an Android location_update payload. fixTimeMs is the fix's capture time
// (Location.getTime()), so this rejects fixes captured more than 120 s ago.
static inline bool fmbValidateFixPayload(NSDictionary *payload, NSString **reasonOut) {
    if ([payload[@"isMock"] boolValue]) {
        if (reasonOut) *reasonOut = @"Rejected mock location flag";
        return false;
    }
    if (!payload[@"lat"] || !payload[@"lon"] || !payload[@"hAcc"] || !payload[@"fixTimeMs"]) {
        if (reasonOut) *reasonOut = @"Missing required coordinate fields";
        return false;
    }
    double lat  = [payload[@"lat"] doubleValue];
    double lon  = [payload[@"lon"] doubleValue];
    double hAcc = [payload[@"hAcc"] doubleValue];
    int64_t fixTimeMs = [payload[@"fixTimeMs"] longLongValue];

    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 || (fabs(lat) < 1e-6 && fabs(lon) < 1e-6)) {
        if (reasonOut) *reasonOut = @"Invalid coordinates";
        return false;
    }
    if (hAcc <= 0.0 || hAcc > kMaxHorizontalAcc) {
        if (reasonOut) *reasonOut = @"Horizontal accuracy out of live bounds";
        return false;
    }
    if (llabs(currentEpochMs() - fixTimeMs) > kMaxCaptureSkewMs) {
        if (reasonOut) *reasonOut = @"Fix timestamp is not live (skew > 120s)";
        return false;
    }
    return true;
}

#pragma mark - Replay protection

// Every payload carries "seq": a per-sender counter that is the sender's epoch ms,
// bumped so it is strictly increasing. A receiver accepts a message only if its seq
// is newer than the last one it accepted from that direction AND within maxAgeMs of
// now, so a captured message can't be replayed later, even after a restart.
// The caller serialises access to *lastSeq.
static inline int64_t fmbNextSeq(int64_t *lastSeq) {
    int64_t now = currentEpochMs();
    int64_t next = (now > *lastSeq) ? now : (*lastSeq + 1);
    *lastSeq = next;
    return next;
}

static inline bool fmbAcceptSeq(int64_t *lastAcceptedSeq, NSDictionary *payload, int64_t maxAgeMs, NSString **reasonOut) {
    id seqObj = payload[@"seq"];
    if (![seqObj isKindOfClass:[NSNumber class]]) {
        if (reasonOut) *reasonOut = @"Missing seq (sender too old?)";
        return false;
    }
    int64_t seq = [seqObj longLongValue];
    if (seq <= *lastAcceptedSeq) {
        if (reasonOut) *reasonOut = @"Replayed or out-of-order message";
        return false;
    }
    if (llabs(currentEpochMs() - seq) > maxAgeMs) {
        if (reasonOut) *reasonOut = @"Message too old or clock skew too large";
        return false;
    }
    *lastAcceptedSeq = seq;
    return true;
}

#endif
