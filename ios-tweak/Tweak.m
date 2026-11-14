// Ping Relay - iOS 15.4.1 (Dopamine Rootless / ElleKit) Tweak + Native Settings Pane
// Two-way live location bridge between an Android device and iOS Find My (fmfd / FindMy.app).
// Strictly enforces live hardware GPS fixes and AES-256-GCM E2EE. If Android goes quiet, the last
// fix is held until it reports again, so Find My never jumps back to this iPhone's own location.

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <CoreLocation/CoreLocation.h>
#import <CommonCrypto/CommonCryptor.h>
#import <Security/Security.h>
#import <objc/runtime.h>
#import <objc/message.h>
#import <notify.h>
#import <dlfcn.h>
#import <ifaddrs.h>
#import <arpa/inet.h>
#import <sys/socket.h>
#import <netinet/in.h>
#import <unistd.h>
#import <sys/time.h>

#import "FMBShared.h"

#pragma mark - Constants & Paths

static NSString *const kConfigPathRootless  = @"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.config.json";
static NSString *const kPairingTxtRootless  = @"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.pairing.txt";
static NSString *const kStatePathRootless   = @"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.state.json";
static NSString *const kFriendsPathRootless = @"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.friends.json";

static NSString *const kConfigPathFallback  = @"/var/mobile/Library/Preferences/com.findmy.livebridge.config.json";
static NSString *const kPairingTxtFallback  = @"/var/mobile/Library/Preferences/com.findmy.livebridge.pairing.txt";
static NSString *const kStatePathFallback   = @"/var/mobile/Library/Preferences/com.findmy.livebridge.state.json";
static NSString *const kFriendsPathFallback = @"/var/mobile/Library/Preferences/com.findmy.livebridge.friends.json";
// findMyId -> {handle, name}, from the friends list in fmfmobile initClient replies.
static NSString *const kFriendMetaPathRootless = @"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.friendmeta.json";
static NSString *const kFriendMetaPathFallback = @"/var/mobile/Library/Preferences/com.findmy.livebridge.friendmeta.json";

static const char *kNotifyLocationUpdated       = "com.findmy.livebridge.location_updated";
static const char *kNotifyFriendsUpdated        = "com.findmy.livebridge.friends_updated";
static const char *kNotifyLocateNeeded          = "com.findmy.livebridge.locate_needed";
static const char *kNotifyRequestFriendsRefresh = "com.findmy.livebridge.request_friends_refresh";
static const char *kNotifyConfigChanged         = "com.findmy.livebridge.config_changed";

static const uint16_t kDefaultHttpPort = 8765;

static NSString *resolvePath(NSString *rootlessPath, NSString *fallbackPath) {
    if ([[NSFileManager defaultManager] fileExistsAtPath:@"/var/jb/var/mobile/Library/Preferences"]) {
        return rootlessPath;
    }
    return fallbackPath;
}

#pragma mark - Shared State & Configuration

static NSString *detectPrimaryLocalIP(void) {
    NSString *bestIP = @"127.0.0.1";
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces) == 0) {
        struct ifaddrs *temp = interfaces;
        while (temp != NULL) {
            if (temp->ifa_addr && temp->ifa_addr->sa_family == AF_INET) {
                NSString *ifName = [NSString stringWithUTF8String:temp->ifa_name];
                char addrBuf[INET_ADDRSTRLEN];
                if (inet_ntop(AF_INET, &((struct sockaddr_in *)temp->ifa_addr)->sin_addr, addrBuf, sizeof(addrBuf))) {
                    NSString *ip = [NSString stringWithUTF8String:addrBuf];
                    if ([ip hasPrefix:@"100."]) {
                        bestIP = ip;
                        break;
                    } else if ([ifName isEqualToString:@"en0"] && ![ip hasPrefix:@"127."]) {
                        bestIP = ip;
                    }
                }
            }
            temp = temp->ifa_next;
        }
        freeifaddrs(interfaces);
    }
    return bestIP;
}

static NSDictionary *parsePairingCode(NSString *code) {
    NSDictionary *parsed = fmbParsePairingCode(code);
    if (!parsed) return nil;
    NSMutableDictionary *cfg = [parsed mutableCopy];
    cfg[@"enabled"] = @YES;
    return cfg;
}

static NSDictionary *generateFreshBridgeConfig(NSString *customRelayUrl, NSString *relayToken, BOOL enabled, BOOL hookDeviceLocation) {
    uint8_t chanBytes[12];
    uint8_t keyBytes[32];
    if (SecRandomCopyBytes(kSecRandomDefault, sizeof(chanBytes), chanBytes) != errSecSuccess ||
        SecRandomCopyBytes(kSecRandomDefault, sizeof(keyBytes), keyBytes) != errSecSuccess) {
        return nil;
    }

    NSMutableString *channelId = [NSMutableString stringWithCapacity:24];
    for (size_t i = 0; i < sizeof(chanBytes); i++) {
        [channelId appendFormat:@"%02x", chanBytes[i]];
    }

    NSData *keyData = [NSData dataWithBytes:keyBytes length:32];
    NSString *keyB64 = [keyData base64EncodedStringWithOptions:0];
    NSString *relayUrl = (customRelayUrl.length > 0) ? customRelayUrl : @"https://ntfy.sh";
    NSString *localIP = detectPrimaryLocalIP();
    NSString *lanEndpoint = [NSString stringWithFormat:@"%@:%u", localIP, kDefaultHttpPort];
    NSString *pairingCode = fmbBuildPairingCode(relayUrl, channelId, keyB64, lanEndpoint, relayToken);

    NSMutableDictionary *cfg = [@{
        @"enabled": @(enabled),
        @"hookDeviceLocation": @(hookDeviceLocation),
        @"relayUrl": relayUrl,
        @"channelId": channelId,
        @"keyBase64": keyB64,
        @"lanEndpoint": lanEndpoint,
        @"pairingCode": pairingCode,
        @"createdAtMs": @(currentEpochMs())
    } mutableCopy];
    if (relayToken.length > 0) cfg[@"relayToken"] = relayToken;

    NSString *cfgPath = resolvePath(kConfigPathRootless, kConfigPathFallback);
    NSData *json = [NSJSONSerialization dataWithJSONObject:cfg options:NSJSONWritingPrettyPrinted error:nil];
    [json writeToFile:cfgPath atomically:YES];
    [pairingCode writeToFile:resolvePath(kPairingTxtRootless, kPairingTxtFallback)
                  atomically:YES
                    encoding:NSUTF8StringEncoding
                       error:nil];
    return cfg;
}

static NSDictionary *loadOrCreateBridgeConfig(void) {
    NSString *cfgPath = resolvePath(kConfigPathRootless, kConfigPathFallback);
    NSData *existing = [NSData dataWithContentsOfFile:cfgPath];
    if (existing) {
        NSDictionary *dict = [NSJSONSerialization JSONObjectWithData:existing options:0 error:nil];
        if ([dict isKindOfClass:[NSDictionary class]] &&
            [dict[@"channelId"] isKindOfClass:[NSString class]] &&
            [dict[@"keyBase64"] isKindOfClass:[NSString class]]) {
            return dict;
        }
    }
    return generateFreshBridgeConfig(@"https://ntfy.sh", nil, YES, YES);
}

static void saveBridgeConfigDictionary(NSDictionary *cfg) {
    if (!cfg) return;
    NSString *cfgPath = resolvePath(kConfigPathRootless, kConfigPathFallback);
    NSData *json = [NSJSONSerialization dataWithJSONObject:cfg options:NSJSONWritingPrettyPrinted error:nil];
    [json writeToFile:cfgPath atomically:YES];
    if ([cfg[@"pairingCode"] isKindOfClass:[NSString class]]) {
        [cfg[@"pairingCode"] writeToFile:resolvePath(kPairingTxtRootless, kPairingTxtFallback)
                              atomically:YES
                                encoding:NSUTF8StringEncoding
                                   error:nil];
    }
    notify_post(kNotifyConfigChanged);
    notify_post(kNotifyLocationUpdated);
}

static BOOL isBridgeEnabled(void) {
    NSDictionary *cfg = loadOrCreateBridgeConfig();
    if (cfg && cfg[@"enabled"] != nil) {
        return [cfg[@"enabled"] boolValue];
    }
    return YES;
}

// Whether findmydeviced/searchpartyd (Find My iPhone device location and the offline
// finding network) also get the Android location. fmfd and FindMy.app always do.
static BOOL isDeviceLocationHookEnabled(void) {
    NSDictionary *cfg = loadOrCreateBridgeConfig();
    return (cfg && cfg[@"hookDeviceLocation"] != nil) ? [cfg[@"hookDeviceLocation"] boolValue] : YES;
}

// New channel + key, keeping the user's settings (and optionally a new relay URL/token).
static void regenerateBridgeConfig(NSDictionary *cur, NSString *relayUrl, NSString *relayToken) {
    generateFreshBridgeConfig(relayUrl ?: (cur[@"relayUrl"] ?: @"https://ntfy.sh"),
                              relayToken,
                              cur[@"enabled"] ? [cur[@"enabled"] boolValue] : YES,
                              cur[@"hookDeviceLocation"] ? [cur[@"hookDeviceLocation"] boolValue] : YES);
    notify_post(kNotifyConfigChanged);
}

static void addRelayAuth(NSMutableURLRequest *req, NSDictionary *cfg) {
    NSString *token = cfg[@"relayToken"];
    if ([token isKindOfClass:[NSString class]] && token.length > 0) {
        [req setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
    }
}

#pragma mark - Live Location Validation & Storage

// Android counts as quiet once no fix or heartbeat has arrived for this long (the slowest
// cadence heartbeats every 10 min). Only the status display uses it: the last fix keeps being
// injected while Android is quiet, so Find My doesn't flip to this iPhone's own location.
static const int64_t kAndroidQuietAfterMs = 20LL * 60LL * 1000LL;

// The stored Android fix, however old. nil when the bridge is off or no fix has been stored
// (or it was cleared); only then do the hooks fall back to the real CoreLocation result.
static NSDictionary *loadLiveState(void) {
    if (!isBridgeEnabled()) return nil;
    NSString *path = resolvePath(kStatePathRootless, kStatePathFallback);
    NSData *data = [NSData dataWithContentsOfFile:path];
    if (!data) return nil;
    NSDictionary *state = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    if (![state isKindOfClass:[NSDictionary class]]) return nil;
    return state;
}

static CLLocation *constructCLLocationFromState(NSDictionary *state) {
    if (!state) return nil;
    double lat    = [state[@"lat"] doubleValue];
    double lon    = [state[@"lon"] doubleValue];
    double hAcc   = [state[@"hAcc"] doubleValue];
    double vAcc   = state[@"vAcc"] ? [state[@"vAcc"] doubleValue] : 8.0;
    double alt    = state[@"alt"]  ? [state[@"alt"] doubleValue]  : 0.0;
    double speed  = state[@"speed"] ? [state[@"speed"] doubleValue] : 0.0;
    double course = state[@"course"] ? [state[@"course"] doubleValue] : -1.0;

    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 || hAcc <= 0.0) {
        return nil;
    }

    CLLocationCoordinate2D coord = CLLocationCoordinate2DMake(lat, lon);
    return [[CLLocation alloc] initWithCoordinate:coord
                                         altitude:alt
                               horizontalAccuracy:hAcc
                                 verticalAccuracy:vAcc
                                           course:course
                                            speed:speed
                                        timestamp:[NSDate date]];
}

static bool validateAndStoreIncomingFix(NSDictionary *payload, NSString **reasonOut) {
    if (!fmbValidateFixPayload(payload, reasonOut)) {
        return false;
    }

    double lat  = [payload[@"lat"] doubleValue];
    double lon  = [payload[@"lon"] doubleValue];
    double hAcc = [payload[@"hAcc"] doubleValue];
    int64_t fixTimeMs = [payload[@"fixTimeMs"] longLongValue];
    int64_t nowMs = currentEpochMs();

    NSDictionary *saved = @{
        @"lat": @(lat),
        @"lon": @(lon),
        @"hAcc": @(hAcc),
        @"vAcc": payload[@"vAcc"] ?: @(8.0),
        @"alt": payload[@"alt"] ?: @(0.0),
        @"speed": payload[@"speed"] ?: @(0.0),
        @"course": payload[@"course"] ?: @(-1.0),
        @"fixTimeMs": @(fixTimeMs),
        @"receivedAtMs": @(nowMs),
        @"provider": payload[@"provider"] ?: @"gps"
    };

    NSString *statePath = resolvePath(kStatePathRootless, kStatePathFallback);
    NSData *json = [NSJSONSerialization dataWithJSONObject:saved options:0 error:nil];
    if (![json writeToFile:statePath atomically:YES]) {
        if (reasonOut) *reasonOut = @"Failed to persist live state";
        return false;
    }

    notify_post(kNotifyLocationUpdated);
    return true;
}

#pragma mark - Native iOS Settings Pane (FMBSettingsViewController)

@interface FMBSettingsViewController : UITableViewController
@end

@implementation FMBSettingsViewController {
    NSTimer *_refreshTimer;
}

- (instancetype)init {
    if (@available(iOS 13.0, *)) {
        self = [super initWithStyle:UITableViewStyleInsetGrouped];
    } else {
        self = [super initWithStyle:UITableViewStyleGrouped];
    }
    return self;
}

// Required by PreferenceLoader PSController protocol when pushed inside Settings.app
- (void)setRootController:(id)rootController {}
- (id)rootController { return nil; }
- (void)setParentController:(id)parentController {}
- (id)parentController { return nil; }
- (void)setSpecifier:(id)specifier {}
- (id)specifier { return nil; }

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = @"Ping Relay";
    if (self.presentingViewController != nil) {
        self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc]
            initWithBarButtonSystemItem:UIBarButtonSystemItemDone
                                 target:self
                                 action:@selector(dismissSelf)];
    }
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self.tableView reloadData];
    _refreshTimer = [NSTimer scheduledTimerWithTimeInterval:3.0
                                                     target:self
                                                   selector:@selector(onTimerTick)
                                                   userInfo:nil
                                                    repeats:YES];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [_refreshTimer invalidate];
    _refreshTimer = nil;
}

- (void)onTimerTick {
    [self.tableView reloadData];
}

- (void)dismissSelf {
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView {
    return 4;
}

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    switch (section) {
        case 0: return 4; // Enable switch, Live Fix status, Coordinates, Friends count
        case 1: return 4; // Pairing Code display, Copy, Paste from Clipboard, Generate New Key
        case 2: return 3; // Relay Server URL, Relay Access Token, Device location toggle
        case 3: return 2; // Force Sync Friends Now, Clear Cached Live Fix
        default: return 0;
    }
}

- (NSString *)tableView:(UITableView *)tableView titleForHeaderInSection:(NSInteger)section {
    switch (section) {
        case 0: return @"Live Location Bridge";
        case 1: return @"E2EE Internet Relay Pairing (fmb1)";
        case 2: return @"Relay Settings";
        case 3: return @"Daemon & Diagnostics";
        default: return nil;
    }
}

- (NSString *)tableView:(UITableView *)tableView titleForFooterInSection:(NSInteger)section {
    if (section == 0) {
        return @"When enabled, fmfd and Find My report your Android phone's verified hardware GPS coordinates. If Android stops updating, its last fix is kept until it reports again.";
    } else if (section == 1) {
        return @"All location packets are end-to-end encrypted with AES-256-GCM before leaving your devices. Copy this code to the Ping app on your Android device.";
    } else if (section == 2) {
        return @"The access token is only needed for a self-hosted ntfy server with authentication; changing the server or token creates a new pairing code. \"Also Replace Device Location\" makes Find My iPhone and the offline finding network report the Android location too. Turn it off to leave them on this iPhone's own GPS (Share My Location still uses Android).";
    }
    return nil;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    NSString *cellId = [NSString stringWithFormat:@"s%ld_r%ld", (long)indexPath.section, (long)indexPath.row];
    UITableViewCell *cell = [tableView dequeueReusableCellWithIdentifier:cellId];
    if (!cell) {
        UITableViewCellStyle style = (indexPath.section == 1 && indexPath.row == 0)
                                     ? UITableViewCellStyleSubtitle
                                     : UITableViewCellStyleValue1;
        cell = [[UITableViewCell alloc] initWithStyle:style reuseIdentifier:cellId];
    }
    cell.selectionStyle = UITableViewCellSelectionStyleDefault;
    cell.accessoryView = nil;
    cell.accessoryType = UITableViewCellAccessoryNone;
    cell.textLabel.textColor = [UIColor labelColor];
    cell.detailTextLabel.textColor = [UIColor secondaryLabelColor];

    NSDictionary *cfg = loadOrCreateBridgeConfig();
    NSDictionary *liveState = loadLiveState();

    if (indexPath.section == 0) {
        if (indexPath.row == 0) {
            cell.selectionStyle = UITableViewCellSelectionStyleNone;
            cell.textLabel.text = @"Enable Location Bridge";
            cell.detailTextLabel.text = nil;
            UISwitch *sw = [[UISwitch alloc] init];
            sw.on = isBridgeEnabled();
            [sw addTarget:self action:@selector(onToggleBridgeSwitch:) forControlEvents:UIControlEventValueChanged];
            cell.accessoryView = sw;
        } else if (indexPath.row == 1) {
            cell.selectionStyle = UITableViewCellSelectionStyleNone;
            cell.textLabel.text = @"Bridge Status";
            if (!isBridgeEnabled()) {
                cell.detailTextLabel.text = @"Paused";
                cell.detailTextLabel.textColor = [UIColor systemOrangeColor];
            } else if (liveState != nil) {
                int64_t quietMs = currentEpochMs() - [liveState[@"receivedAtMs"] longLongValue];
                if (quietMs < kAndroidQuietAfterMs) {
                    cell.detailTextLabel.text = @"Active";
                    cell.detailTextLabel.textColor = [UIColor systemGreenColor];
                } else {
                    int64_t mins = quietMs / 60000;
                    NSString *age = mins >= 60 ? [NSString stringWithFormat:@"%lldh %lldm", mins / 60, mins % 60]
                                               : [NSString stringWithFormat:@"%lldm", mins];
                    cell.detailTextLabel.text = [NSString stringWithFormat:@"Holding last fix (%@ ago)", age];
                    cell.detailTextLabel.textColor = [UIColor systemOrangeColor];
                }
            } else {
                cell.detailTextLabel.text = @"Waiting for Android GPS";
                cell.detailTextLabel.textColor = [UIColor secondaryLabelColor];
            }
        } else if (indexPath.row == 2) {
            cell.selectionStyle = UITableViewCellSelectionStyleNone;
            cell.textLabel.text = @"Android GPS Fix";
            if (liveState != nil) {
                cell.detailTextLabel.text = [NSString stringWithFormat:@"%.5f, %.5f (±%.0fm)",
                                             [liveState[@"lat"] doubleValue],
                                             [liveState[@"lon"] doubleValue],
                                             [liveState[@"hAcc"] doubleValue]];
            } else {
                cell.detailTextLabel.text = @"None active";
            }
        } else if (indexPath.row == 3) {
            cell.selectionStyle = UITableViewCellSelectionStyleNone;
            cell.textLabel.text = @"Friends Synced (fmfd)";
            NSData *fData = [NSData dataWithContentsOfFile:resolvePath(kFriendsPathRootless, kFriendsPathFallback)];
            NSDictionary *fDict = fData ? [NSJSONSerialization JSONObjectWithData:fData options:0 error:nil] : nil;
            NSArray *arr = [fDict isKindOfClass:[NSDictionary class]] ? fDict[@"friends"] : nil;
            cell.detailTextLabel.text = [NSString stringWithFormat:@"%lu friend(s)", (unsigned long)(arr ? arr.count : 0)];
        }
    } else if (indexPath.section == 1) {
        if (indexPath.row == 0) {
            cell.textLabel.text = @"Active Pairing Code";
            cell.detailTextLabel.text = cfg[@"pairingCode"] ?: @"Not configured";
            cell.detailTextLabel.numberOfLines = 2;
            cell.detailTextLabel.font = [UIFont monospacedSystemFontOfSize:11.0 weight:UIFontWeightRegular];
        } else if (indexPath.row == 1) {
            cell.textLabel.text = @"Copy Pairing Code";
            cell.textLabel.textColor = [UIColor systemBlueColor];
            cell.detailTextLabel.text = nil;
        } else if (indexPath.row == 2) {
            cell.textLabel.text = @"Paste Pairing Code from Clipboard";
            cell.textLabel.textColor = [UIColor systemBlueColor];
            cell.detailTextLabel.text = nil;
        } else if (indexPath.row == 3) {
            cell.textLabel.text = @"Generate New 256-Bit Pairing Key";
            cell.textLabel.textColor = [UIColor systemBlueColor];
            cell.detailTextLabel.text = nil;
        }
    } else if (indexPath.section == 2) {
        if (indexPath.row == 0) {
            cell.textLabel.text = @"Internet Relay Server";
            cell.detailTextLabel.text = cfg[@"relayUrl"] ?: @"https://ntfy.sh";
            cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
        } else if (indexPath.row == 1) {
            cell.textLabel.text = @"Relay Access Token";
            NSString *token = cfg[@"relayToken"];
            cell.detailTextLabel.text = ([token isKindOfClass:[NSString class]] && token.length > 4)
                                        ? [NSString stringWithFormat:@"Set (…%@)", [token substringFromIndex:token.length - 4]]
                                        : @"None";
            cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
        } else if (indexPath.row == 2) {
            cell.selectionStyle = UITableViewCellSelectionStyleNone;
            cell.textLabel.text = @"Also Replace Device Location";
            cell.detailTextLabel.text = nil;
            UISwitch *sw = [[UISwitch alloc] init];
            sw.on = isDeviceLocationHookEnabled();
            [sw addTarget:self action:@selector(onToggleDeviceHookSwitch:) forControlEvents:UIControlEventValueChanged];
            cell.accessoryView = sw;
        }
    } else if (indexPath.section == 3) {
        if (indexPath.row == 0) {
            cell.textLabel.text = @"Push Friends List to Android Now";
            cell.textLabel.textColor = [UIColor systemBlueColor];
            cell.detailTextLabel.text = nil;
        } else if (indexPath.row == 1) {
            cell.textLabel.text = @"Clear Cached Android Fix";
            cell.textLabel.textColor = [UIColor systemRedColor];
            cell.detailTextLabel.text = nil;
        }
    }

    return cell;
}

- (void)onToggleBridgeSwitch:(UISwitch *)sender {
    NSMutableDictionary *cfg = [loadOrCreateBridgeConfig() mutableCopy];
    if (!cfg) return;
    cfg[@"enabled"] = @(sender.isOn);
    saveBridgeConfigDictionary(cfg);
    [self.tableView reloadData];
}

- (void)onToggleDeviceHookSwitch:(UISwitch *)sender {
    NSMutableDictionary *cfg = [loadOrCreateBridgeConfig() mutableCopy];
    if (!cfg) return;
    cfg[@"hookDeviceLocation"] = @(sender.isOn);
    saveBridgeConfigDictionary(cfg);
    [self.tableView reloadData];
}

- (void)showToastAlert:(NSString *)title message:(NSString *)message {
    UIAlertController *ac = [UIAlertController alertControllerWithTitle:title
                                                                message:message
                                                         preferredStyle:UIAlertControllerStyleAlert];
    [ac addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
    [self presentViewController:ac animated:YES completion:nil];
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    NSMutableDictionary *cfg = [loadOrCreateBridgeConfig() mutableCopy];

    if (indexPath.section == 1) {
        if (indexPath.row == 0 || indexPath.row == 1) {
            NSString *code = cfg[@"pairingCode"] ?: @"";
            [UIPasteboard generalPasteboard].string = code;
            [self showToastAlert:@"Copied Pairing Code"
                         message:@"The fmb1 E2EE pairing code has been copied to your clipboard. Paste it into the Ping app on your Android device."];
        } else if (indexPath.row == 2) {
            NSString *clip = [UIPasteboard generalPasteboard].string;
            NSDictionary *parsed = parsePairingCode(clip);
            if (parsed) {
                NSMutableDictionary *merged = [parsed mutableCopy];
                merged[@"enabled"] = cfg[@"enabled"] ?: @YES;
                merged[@"hookDeviceLocation"] = cfg[@"hookDeviceLocation"] ?: @YES;
                saveBridgeConfigDictionary(merged);
                [self.tableView reloadData];
                [self showToastAlert:@"Paired Successfully"
                             message:@"Saved the fmb1 pairing code from your clipboard and connected to the E2EE channel."];
            } else {
                [self showToastAlert:@"Invalid Pairing Code"
                             message:@"Your clipboard does not contain a valid fmb1:<relayUrl>:<channelId>:<base64Key> string."];
            }
        } else if (indexPath.row == 3) {
            regenerateBridgeConfig(cfg, nil, cfg[@"relayToken"]);
            [self.tableView reloadData];
            [self showToastAlert:@"Generated New Key"
                         message:@"Created a fresh 256-bit AES-GCM pairing code and copied it to /var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.pairing.txt."];
        }
    } else if (indexPath.section == 2) {
        if (indexPath.row == 0) {
            UIAlertController *ac = [UIAlertController alertControllerWithTitle:@"Internet Relay Server URL"
                                                                        message:@"Enter the base URL for the E2EE pub/sub relay (default: https://ntfy.sh):"
                                                                 preferredStyle:UIAlertControllerStyleAlert];
            [ac addTextFieldWithConfigurationHandler:^(UITextField *tf) {
                tf.text = cfg[@"relayUrl"] ?: @"https://ntfy.sh";
                tf.keyboardType = UIKeyboardTypeURL;
            }];
            [ac addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
            [ac addAction:[UIAlertAction actionWithTitle:@"Save" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
                NSString *newUrl = [ac.textFields.firstObject.text stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
                while ([newUrl hasSuffix:@"/"]) newUrl = [newUrl substringToIndex:newUrl.length - 1];
                if ([newUrl hasPrefix:@"http"]) {
                    regenerateBridgeConfig(cfg, newUrl, cfg[@"relayToken"]);
                    [self.tableView reloadData];
                }
            }]];
            [self presentViewController:ac animated:YES completion:nil];
        } else if (indexPath.row == 1) {
            UIAlertController *ac = [UIAlertController alertControllerWithTitle:@"Relay Access Token"
                                                                        message:@"Access token for a self-hosted ntfy server (tk_…). Leave empty for none. Copy the updated pairing code to Android afterwards."
                                                                 preferredStyle:UIAlertControllerStyleAlert];
            [ac addTextFieldWithConfigurationHandler:^(UITextField *tf) {
                tf.text = cfg[@"relayToken"] ?: @"";
                tf.autocapitalizationType = UITextAutocapitalizationTypeNone;
                tf.autocorrectionType = UITextAutocorrectionTypeNo;
                tf.secureTextEntry = YES;
            }];
            [ac addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
            [ac addAction:[UIAlertAction actionWithTitle:@"Save" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
                NSString *token = [ac.textFields.firstObject.text stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
                if (token.length > 0 && !fmbIsSafeParam(token, @"._-")) {
                    [self showToastAlert:@"Invalid Token" message:@"Tokens may only contain letters, digits, '.', '_' and '-'."];
                    return;
                }
                // Same channel and key; only the pairing code's token part changes.
                NSMutableDictionary *updated = [cfg mutableCopy];
                NSString *lan = cfg[@"lanEndpoint"] ?: fmbParsePairingCode(cfg[@"pairingCode"])[@"lanEndpoint"];
                if (token.length > 0) updated[@"relayToken"] = token; else [updated removeObjectForKey:@"relayToken"];
                updated[@"pairingCode"] = fmbBuildPairingCode(cfg[@"relayUrl"], cfg[@"channelId"], cfg[@"keyBase64"],
                                                              lan, token.length > 0 ? token : nil);
                saveBridgeConfigDictionary(updated);
                [self.tableView reloadData];
            }]];
            [self presentViewController:ac animated:YES completion:nil];
        }
    } else if (indexPath.section == 3) {
        if (indexPath.row == 0) {
            notify_post(kNotifyRequestFriendsRefresh);
            notify_post(kNotifyFriendsUpdated);
            [self showToastAlert:@"Sync Triggered"
                         message:@"Requested an immediate encrypted downlink push of friends' locations to your Android phone."];
        } else if (indexPath.row == 1) {
            [[NSFileManager defaultManager] removeItemAtPath:resolvePath(kStatePathRootless, kStatePathFallback) error:nil];
            notify_post(kNotifyLocationUpdated);
            [self.tableView reloadData];
        }
    }
}

@end

#pragma mark - Role 2: CoreLocation & FMF Hooks (fmfd, findmydeviced, searchpartyd, FindMy)

static NSHashTable<CLLocationManager *> *gActiveManagers = nil;
static NSMutableSet<NSString *> *gSwizzledDelegateClasses = nil;
static NSObject *gManagersLock = nil;

// findmydeviced / searchpartyd only inject when "Also Replace Device Location" is on.
static BOOL gIsDeviceDaemon = NO;
static volatile BOOL gDeviceHookEnabled = YES;

static NSDictionary *injectedLiveState(void) {
    if (gIsDeviceDaemon && !gDeviceHookEnabled) return nil;
    return loadLiveState();
}

static void pushLiveLocationToActiveManagers(void) {
    NSDictionary *state = injectedLiveState();
    CLLocation *liveLoc = constructCLLocationFromState(state);
    if (!liveLoc) return;

    NSArray<CLLocationManager *> *managers = nil;
    @synchronized (gManagersLock) {
        managers = [gActiveManagers allObjects];
    }

    for (CLLocationManager *mgr in managers) {
        id<CLLocationManagerDelegate> del = mgr.delegate;
        if (del && [del respondsToSelector:@selector(locationManager:didUpdateLocations:)]) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [del locationManager:mgr didUpdateLocations:@[liveLoc]];
            });
        }
    }
}

static void (*orig_delegate_didUpdateLocations)(id, SEL, CLLocationManager *, NSArray<CLLocation *> *) = NULL;

static void hooked_delegate_didUpdateLocations(id self, SEL _cmd, CLLocationManager *manager, NSArray<CLLocation *> *locations) {
    NSDictionary *state = injectedLiveState();
    CLLocation *liveLoc = constructCLLocationFromState(state);
    NSArray<CLLocation *> *replacement = liveLoc ? @[liveLoc] : locations;

    NSString *key = [NSString stringWithFormat:@"orig_%@", NSStringFromClass([self class])];
    NSValue *impVal = objc_getAssociatedObject([self class], NSSelectorFromString(key));
    if (impVal) {
        void (*origImp)(id, SEL, CLLocationManager *, NSArray<CLLocation *> *) = [impVal pointerValue];
        if (origImp) {
            origImp(self, _cmd, manager, replacement);
            return;
        }
    }
    if (orig_delegate_didUpdateLocations) {
        orig_delegate_didUpdateLocations(self, _cmd, manager, replacement);
    }
}

static void swizzleDelegateClassIfNeeded(Class cls) {
    if (!cls) return;
    @synchronized (gManagersLock) {
        NSString *clsName = NSStringFromClass(cls);
        if ([gSwizzledDelegateClasses containsObject:clsName]) return;

        SEL sel = @selector(locationManager:didUpdateLocations:);
        Method m = class_getInstanceMethod(cls, sel);
        if (m) {
            IMP origImp = method_getImplementation(m);
            if (origImp != (IMP)hooked_delegate_didUpdateLocations) {
                NSString *key = [NSString stringWithFormat:@"orig_%@", clsName];
                objc_setAssociatedObject(cls, NSSelectorFromString(key), [NSValue valueWithPointer:origImp], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
                method_setImplementation(m, (IMP)hooked_delegate_didUpdateLocations);
                [gSwizzledDelegateClasses addObject:clsName];
            }
        }
    }
}

static CLLocation *(*orig_CLLocationManager_location)(id, SEL) = NULL;
static CLLocation *hooked_CLLocationManager_location(CLLocationManager *self, SEL _cmd) {
    @synchronized (gManagersLock) {
        [gActiveManagers addObject:self];
    }
    NSDictionary *state = injectedLiveState();
    CLLocation *liveLoc = constructCLLocationFromState(state);
    if (liveLoc) {
        return liveLoc;
    }
    return orig_CLLocationManager_location ? orig_CLLocationManager_location(self, _cmd) : nil;
}

static void (*orig_CLLocationManager_setDelegate)(id, SEL, id) = NULL;
static void hooked_CLLocationManager_setDelegate(CLLocationManager *self, SEL _cmd, id delegate) {
    @synchronized (gManagersLock) {
        [gActiveManagers addObject:self];
    }
    if (delegate) {
        swizzleDelegateClassIfNeeded([delegate class]);
    }
    if (orig_CLLocationManager_setDelegate) {
        orig_CLLocationManager_setDelegate(self, _cmd, delegate);
    }
}

static void (*orig_CLLocationManager_startUpdatingLocation)(id, SEL) = NULL;
static void hooked_CLLocationManager_startUpdatingLocation(CLLocationManager *self, SEL _cmd) {
    @synchronized (gManagersLock) {
        [gActiveManagers addObject:self];
    }
    if (orig_CLLocationManager_startUpdatingLocation) {
        orig_CLLocationManager_startUpdatingLocation(self, _cmd);
    }
    pushLiveLocationToActiveManagers();
    notify_post(kNotifyLocateNeeded);
}

static void (*orig_CLLocationManager_requestLocation)(id, SEL) = NULL;
static void hooked_CLLocationManager_requestLocation(CLLocationManager *self, SEL _cmd) {
    @synchronized (gManagersLock) {
        [gActiveManagers addObject:self];
    }
    if (orig_CLLocationManager_requestLocation) {
        orig_CLLocationManager_requestLocation(self, _cmd);
    }
    pushLiveLocationToActiveManagers();
    notify_post(kNotifyLocateNeeded);
}

// While a fix is being injected, report it as a normal on-device fix; otherwise defer to CoreLocation.
static BOOL (*orig_isSimulatedBySoftware)(id, SEL) = NULL;
static BOOL (*orig_isProducedByAccessory)(id, SEL) = NULL;
static BOOL hooked_isSimulatedBySoftware(id self, SEL _cmd) {
    if (injectedLiveState() != nil) return NO;
    return orig_isSimulatedBySoftware ? orig_isSimulatedBySoftware(self, _cmd) : NO;
}
static BOOL hooked_isProducedByAccessory(id self, SEL _cmd) {
    if (injectedLiveState() != nil) return NO;
    return orig_isProducedByAccessory ? orig_isProducedByAccessory(self, _cmd) : NO;
}

#pragma mark - Intercepting Friends' Locations from fmfmobile.icloud.com in fmfd / FindMy

static void fmbDebugLog(NSString *msg);

/**
 * Updates the shared friends file. [friends] (nil = leave as is) are merged by id, newest
 * timestamp wins, so a partial update never wipes other friends. [meDeviceActive] nil = keep.
 * Several processes write this file; each write is atomic.
 */
static void fmbWriteFriendsState(NSArray<NSDictionary *> *friends, NSNumber *meDeviceActive) {
    static NSObject *lock;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ lock = [NSObject new]; });
    @synchronized (lock) {
        NSString *path = resolvePath(kFriendsPathRootless, kFriendsPathFallback);
        NSData *existing = [NSData dataWithContentsOfFile:path];
        NSDictionary *old = existing ? [NSJSONSerialization JSONObjectWithData:existing options:0 error:nil] : nil;
        if (![old isKindOfClass:[NSDictionary class]]) old = @{};

        NSMutableDictionary<NSString *, NSDictionary *> *byId = [NSMutableDictionary dictionary];
        for (NSDictionary *f in ([old[@"friends"] isKindOfClass:[NSArray class]] ? old[@"friends"] : @[])) {
            if ([f isKindOfClass:[NSDictionary class]] && f[@"id"]) byId[f[@"id"]] = f;
        }
        for (NSDictionary *f in friends ?: @[]) {
            NSDictionary *prev = byId[f[@"id"]];
            if (!prev || [f[@"timestampMs"] longLongValue] >= [prev[@"timestampMs"] longLongValue]) byId[f[@"id"]] = f;
        }
        NSArray *sorted = [byId.allValues sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
            return [[a[@"name"] description] localizedCaseInsensitiveCompare:[b[@"name"] description]];
        }];
        NSDictionary *state = @{
            @"updatedAtMs": @(currentEpochMs()),
            @"meDeviceActive": meDeviceActive ?: (old[@"meDeviceActive"] ?: @YES),
            @"friends": sorted
        };
        NSData *out = [NSJSONSerialization dataWithJSONObject:state options:0 error:nil];
        if (out && ![out isEqualToData:existing] && [out writeToFile:path atomically:YES]) {
            notify_post(kNotifyFriendsUpdated);
        }
    }
}

/** Converts a decrypted FMFLocation (FMF.framework, iOS 15) into the friend format Android expects. */
static NSDictionary *fmbFriendFromFMFLocation(id loc) {
    @try {
        CLLocation *cl = [loc valueForKey:@"location"];
        if (![cl isKindOfClass:[CLLocation class]]) return nil;
        id handle = [loc valueForKey:@"handle"];
        NSString *ident = [handle valueForKey:@"identifier"];
        NSString *serverId = [handle valueForKey:@"serverId"];
        NSString *name = [handle respondsToSelector:NSSelectorFromString(@"prettyName")] ? [handle valueForKey:@"prettyName"] : nil;
        NSString *address = [loc valueForKey:@"longAddress"];
        if (![address isKindOfClass:[NSString class]] || address.length == 0) address = [loc valueForKey:@"shortAddressString"];
        NSDate *ts = [loc valueForKey:@"timestamp"];
        if (![ts isKindOfClass:[NSDate class]]) ts = cl.timestamp;
        NSString *fid = [serverId isKindOfClass:[NSString class]] && serverId.length ? serverId : ident;
        if (![fid isKindOfClass:[NSString class]] || fid.length == 0) return nil;
        return @{
            @"id": fid,
            @"handle": [ident isKindOfClass:[NSString class]] ? ident : @"",
            @"name": [name isKindOfClass:[NSString class]] && name.length ? name : (ident ?: fid),
            @"lat": @(cl.coordinate.latitude),
            @"lon": @(cl.coordinate.longitude),
            @"hAcc": @(cl.horizontalAccuracy > 0 ? cl.horizontalAccuracy : 50.0),
            @"alt": @(cl.altitude),
            @"timestampMs": @((int64_t)(ts.timeIntervalSince1970 * 1000.0)),
            @"address": [address isKindOfClass:[NSString class]] ? address : @"",
            @"status": @"live"
        };
    } @catch (NSException *e) {
        return nil;
    }
}

static void fmbHandleLocationSet(id locations, NSString *source) {
    if (![locations conformsToProtocol:@protocol(NSFastEnumeration)]) return;
    NSArray *items = [locations isKindOfClass:[NSSet class]] ? [(NSSet *)locations allObjects]
                   : ([locations isKindOfClass:[NSArray class]] ? locations : nil);
    if (!items) return;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSMutableArray *friends = [NSMutableArray array];
        for (id loc in items) {
            NSDictionary *f = fmbFriendFromFMFLocation(loc);
            if (f) [friends addObject:f];
        }
        fmbDebugLog([NSString stringWithFormat:@"%@ count=%lu converted=%lu", source,
                     (unsigned long)items.count, (unsigned long)friends.count]);
        if (friends.count > 0) fmbWriteFriendsState(friends, nil);
    });
}

static void (*orig_DM_setLocations)(id, SEL, id) = NULL;
static void hooked_DM_setLocations(id self, SEL _cmd, id locations) {
    if (orig_DM_setLocations) orig_DM_setLocations(self, _cmd, locations);
    fmbHandleLocationSet(locations, @"FMFSessionDataManager.setLocations");
}
static void (*orig_S_didUpdateLocations)(id, SEL, id) = NULL;
static void hooked_S_didUpdateLocations(id self, SEL _cmd, id locations) {
    if (orig_S_didUpdateLocations) orig_S_didUpdateLocations(self, _cmd, locations);
    fmbHandleLocationSet(locations, @"FMFSession.didUpdateLocations");
}
static void (*orig_SP_didUpdateLocations)(id, SEL, id) = NULL;
static void hooked_SP_didUpdateLocations(id self, SEL _cmd, id locations) {
    if (orig_SP_didUpdateLocations) orig_SP_didUpdateLocations(self, _cmd, locations);
    fmbHandleLocationSet(locations, @"FMFSessionProxy.didUpdateLocations");
}

/**
 * iOS 15 delivers friends' decrypted locations to the Find My app as SPSecureLocation objects
 * (SPOwner/searchpartyd). They only carry a findMyId, so names/handles come from the map saved
 * from the fmfmobile friends list.
 */
static NSDictionary *fmbFriendFromSecureLocation(id sl, NSDictionary *meta, BOOL *matched) {
    @try {
        NSString *fid = [sl valueForKey:@"findMyId"];
        if (![fid isKindOfClass:[NSString class]] || fid.length == 0) return nil;
        double lat = [[sl valueForKey:@"latitude"] doubleValue];
        double lon = [[sl valueForKey:@"longitude"] doubleValue];
        if (lat == 0 && lon == 0) return nil;
        double hAcc = [[sl valueForKey:@"horizontalAccuracy"] doubleValue];
        NSDate *ts = [sl valueForKey:@"timestamp"];
        NSString *label = [sl valueForKey:@"locationLabel"];
        NSDictionary *m = [meta[fid] isKindOfClass:[NSDictionary class]] ? meta[fid] : nil;
        if (matched) *matched = (m != nil);
        NSString *handle = [m[@"handle"] isKindOfClass:[NSString class]] ? m[@"handle"] : @"";
        NSString *name = [m[@"name"] isKindOfClass:[NSString class]] && [m[@"name"] length] ? m[@"name"] : (handle.length ? handle : @"Friend");
        return @{
            @"id": fid,
            @"handle": handle,
            @"name": name,
            @"lat": @(lat),
            @"lon": @(lon),
            @"hAcc": @(hAcc > 0 ? hAcc : 50.0),
            @"alt": @([[sl valueForKey:@"altitude"] doubleValue]),
            // 0 = unknown: Android shows no age, and any timed fix wins the merge over it.
            @"timestampMs": @([ts isKindOfClass:[NSDate class]] ? (int64_t)(ts.timeIntervalSince1970 * 1000.0) : 0),
            @"address": [label isKindOfClass:[NSString class]] ? label : @"",
            @"status": @"live"
        };
    } @catch (NSException *e) {
        return nil;
    }
}

static void fmbHandleSecureLocations(id locations, NSString *source) {
    NSArray *items = [locations isKindOfClass:[NSArray class]] ? locations
                   : ([locations isKindOfClass:[NSSet class]] ? [(NSSet *)locations allObjects] : nil);
    if (items.count == 0) return;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSData *metaData = [NSData dataWithContentsOfFile:resolvePath(kFriendMetaPathRootless, kFriendMetaPathFallback)];
        NSDictionary *meta = metaData ? [NSJSONSerialization JSONObjectWithData:metaData options:0 error:nil] : nil;
        if (![meta isKindOfClass:[NSDictionary class]]) meta = @{};
        NSMutableArray *friends = [NSMutableArray array];
        NSUInteger matchedCount = 0;
        for (id sl in items) {
            BOOL matched = NO;
            NSDictionary *f = fmbFriendFromSecureLocation(sl, meta, &matched);
            if (f) {
                [friends addObject:f];
                if (matched) matchedCount++;
            }
        }
        fmbDebugLog([NSString stringWithFormat:@"%@ count=%lu converted=%lu matchedNames=%lu", source,
                     (unsigned long)items.count, (unsigned long)friends.count, (unsigned long)matchedCount]);
        if (friends.count > 0) fmbWriteFriendsState(friends, nil);
    });
}

static void (*orig_SLM_receivedUpdatedLocations)(id, SEL, id) = NULL;
static void hooked_SLM_receivedUpdatedLocations(id self, SEL _cmd, id locations) {
    if (orig_SLM_receivedUpdatedLocations) orig_SLM_receivedUpdatedLocations(self, _cmd, locations);
    fmbHandleSecureLocations(locations, @"SPSecureLocationsManager.receivedUpdatedLocations");
}
static void (*orig_SLR_setLocations)(id, SEL, id) = NULL;
static void hooked_SLR_setLocations(id self, SEL _cmd, id locations) {
    if (orig_SLR_setLocations) orig_SLR_setLocations(self, _cmd, locations);
    fmbHandleSecureLocations(locations, @"SPSecureLocationsSubscriptionResult.setLocations");
}
static id (*orig_SLR_initWithCoder)(id, SEL, id) = NULL;
static id hooked_SLR_initWithCoder(id self, SEL _cmd, id coder) {
    id result = orig_SLR_initWithCoder ? orig_SLR_initWithCoder(self, _cmd, coder) : nil;
    @try {
        fmbHandleSecureLocations([result valueForKey:@"locations"], @"SPSecureLocationsSubscriptionResult.initWithCoder");
    } @catch (NSException *e) {}
    return result;
}

#pragma mark - On-demand friend-location fetch (findmydeviced only)

// Asks searchpartyd for friends' current locations, instead of waiting for the Find My app to.
// Runs only in findmydeviced, which carries com.apple.icloud.searchpartyd.securelocations.access
// and already has Sharing.framework loaded. Triggered by a refresh request from Android.
static int64_t gLastSecureFetchMs = 0;
static id gSecureLocationsManager = nil; // retained; created once

static NSArray<NSString *> *fmbKnownFriendIds(void) {
    NSData *metaData = [NSData dataWithContentsOfFile:resolvePath(kFriendMetaPathRootless, kFriendMetaPathFallback)];
    NSDictionary *meta = metaData ? [NSJSONSerialization JSONObjectWithData:metaData options:0 error:nil] : nil;
    return [meta isKindOfClass:[NSDictionary class]] ? meta.allKeys : @[];
}

static void fmbFetchSecureLocationsNow(NSString *reason) {
    Class mgrCls = objc_getClass("SPSecureLocationsManager");
    if (!mgrCls) { fmbDebugLog(@"fetch: SPSecureLocationsManager unavailable"); return; }

    int64_t now = currentEpochMs();
    @synchronized (mgrCls) {
        if (now - gLastSecureFetchMs < 8000) return; // throttle bursts of refresh requests
        gLastSecureFetchMs = now;
    }

    NSArray<NSString *> *ids = fmbKnownFriendIds();
    if (ids.count == 0) { fmbDebugLog(@"fetch: no known friend ids yet"); return; }

    @try {
        if (!gSecureLocationsManager) {
            gSecureLocationsManager = [[mgrCls alloc] init];
        }
        id mgr = gSecureLocationsManager;
        SEL sel = NSSelectorFromString(@"subscribeAndFetchLocationForIds:clientApp:completion:");
        if (![mgr respondsToSelector:sel]) { fmbDebugLog(@"fetch: subscribe selector missing"); return; }

        void (^completion)(id, id) = ^(id result, id error) {
            @try {
                // The completion delivers the locations array directly (or a result wrapper around it).
                id locs = ([result isKindOfClass:[NSArray class]] || [result isKindOfClass:[NSSet class]])
                          ? result
                          : ([result respondsToSelector:@selector(locations)] ? [result performSelector:@selector(locations)] : nil);
                NSUInteger n = [locs respondsToSelector:@selector(count)] ? [locs count] : 0;
                fmbDebugLog([NSString stringWithFormat:@"fetch(%@) completion: locations=%lu error=%@",
                             reason, (unsigned long)n, error ? [error description] : @"nil"]);
                if (locs) fmbHandleSecureLocations(locs, @"fetch.completion");
            } @catch (NSException *e) {
                fmbDebugLog([NSString stringWithFormat:@"fetch completion exception: %@", e]);
            }
        };

        fmbDebugLog([NSString stringWithFormat:@"fetch(%@): requesting %lu ids", reason, (unsigned long)ids.count]);
        ((void (*)(id, SEL, id, id, id))objc_msgSend)(mgr, sel, ids, @"com.apple.findmy", completion);
    } @catch (NSException *e) {
        gSecureLocationsManager = nil;
        fmbDebugLog([NSString stringWithFormat:@"fetch exception: %@", e]);
    }
}

static void extractAndStoreFriendsFromFMFJson(NSDictionary *json) {
    if (![json isKindOfClass:[NSDictionary class]]) return;

    NSArray *locations = json[@"locations"];
    NSArray *following = json[@"following"];
    NSArray *contactDetails = json[@"contactDetails"];
    NSDictionary *serverContext = json[@"serverContext"];

    if (![locations isKindOfClass:[NSArray class]] && ![following isKindOfClass:[NSArray class]]) {
        return;
    }

    // Our own addresses, so they're never mistaken for a friend's handle.
    NSMutableSet<NSString *> *myHandles = [NSMutableSet set];
    NSDictionary *myInfo = [json[@"myInfo"] isKindOfClass:[NSDictionary class]] ? json[@"myInfo"] : nil;
    for (NSString *e in ([myInfo[@"emails"] isKindOfClass:[NSArray class]] ? myInfo[@"emails"] : @[])) {
        if ([e isKindOfClass:[NSString class]]) [myHandles addObject:e.lowercaseString];
    }

    NSMutableDictionary<NSString *, NSMutableDictionary *> *friendMeta = [NSMutableDictionary dictionary];
    if ([following isKindOfClass:[NSArray class]]) {
        for (NSDictionary *f in following) {
            if (![f isKindOfClass:[NSDictionary class]]) continue;
            NSString *fid = f[@"id"];
            // The friend's own handle is in invitationAcceptedHandles; invitationFromHandles is
            // the address the share was sent to (ours). Skip anything that's one of our addresses.
            NSString *handle = nil;
            for (NSString *key in @[@"invitationAcceptedHandles", @"invitationFromHandles"]) {
                NSArray *list = f[key];
                if (![list isKindOfClass:[NSArray class]]) continue;
                for (NSString *candidate in list) {
                    if ([candidate isKindOfClass:[NSString class]] && candidate.length &&
                        ![myHandles containsObject:candidate.lowercaseString]) {
                        handle = candidate;
                        break;
                    }
                }
                if (handle) break;
            }
            if (!handle) handle = f[@"handle"] ?: fid;
            if (fid) {
                friendMeta[fid] = [@{
                    @"id": fid,
                    @"handle": handle ?: fid,
                    @"name": handle ?: fid
                } mutableCopy];
            }
        }
    }

    if ([contactDetails isKindOfClass:[NSArray class]]) {
        for (NSDictionary *c in contactDetails) {
            if (![c isKindOfClass:[NSDictionary class]]) continue;
            NSString *cid = c[@"id"] ?: c[@"userId"];
            NSString *first = c[@"firstName"] ?: @"";
            NSString *last  = c[@"lastName"] ?: @"";
            NSString *fullName = [[NSString stringWithFormat:@"%@ %@", first, last]
                                  stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
            if (cid && fullName.length > 0 && friendMeta[cid]) {
                friendMeta[cid][@"name"] = fullName;
            }
        }
    }

    if (friendMeta.count > 0) {
        NSData *metaJson = [NSJSONSerialization dataWithJSONObject:friendMeta options:0 error:nil];
        [metaJson writeToFile:resolvePath(kFriendMetaPathRootless, kFriendMetaPathFallback) atomically:YES];
    }

    NSMutableArray<NSDictionary *> *parsedFriends = [NSMutableArray array];
    if ([locations isKindOfClass:[NSArray class]]) {
        for (NSDictionary *locEntry in locations) {
            if (![locEntry isKindOfClass:[NSDictionary class]]) continue;
            NSString *fid = locEntry[@"id"];
            NSDictionary *locObj = [locEntry[@"location"] isKindOfClass:[NSDictionary class]]
                                   ? locEntry[@"location"]
                                   : locEntry;
            if (!locObj[@"latitude"] || !locObj[@"longitude"]) continue;

            double lat  = [locObj[@"latitude"] doubleValue];
            double lon  = [locObj[@"longitude"] doubleValue];
            double hAcc = locObj[@"horizontalAccuracy"] ? [locObj[@"horizontalAccuracy"] doubleValue] : 10.0;
            double alt  = locObj[@"altitude"] ? [locObj[@"altitude"] doubleValue] : 0.0;
            int64_t ts  = locObj[@"timestamp"] ? [locObj[@"timestamp"] longLongValue] : 0; // 0 = unknown

            NSString *addressStr = @"";
            NSDictionary *addr = locObj[@"address"];
            if ([addr isKindOfClass:[NSDictionary class]]) {
                NSArray *lines = addr[@"formattedAddressLines"];
                if ([lines isKindOfClass:[NSArray class]] && lines.count > 0) {
                    addressStr = [lines componentsJoinedByString:@", "];
                } else if ([addr[@"locality"] isKindOfClass:[NSString class]]) {
                    addressStr = addr[@"locality"];
                }
            }

            NSDictionary *meta = fid ? friendMeta[fid] : nil;
            NSString *handle = meta[@"handle"] ?: (locEntry[@"handle"] ?: (fid ?: @"unknown"));
            NSString *name   = meta[@"name"]   ?: handle;

            [parsedFriends addObject:@{
                @"id": fid ?: handle,
                @"handle": handle,
                @"name": name,
                @"lat": @(lat),
                @"lon": @(lon),
                @"hAcc": @(hAcc),
                @"alt": @(alt),
                @"timestampMs": @(ts),
                @"address": addressStr ?: @"",
                @"status": @"live"
            }];
        }
    }

    BOOL sendMyLocation = YES;
    if ([serverContext isKindOfClass:[NSDictionary class]] && serverContext[@"sendMyLocation"] != nil) {
        sendMyLocation = [serverContext[@"sendMyLocation"] boolValue];
    }

    // Since iOS 15 these replies carry no locations (friends' locations are end-to-end
    // encrypted; see fmbHandleLocationSet below), so only merge if any were found.
    fmbWriteFriendsState(parsedFriends.count > 0 ? parsedFriends : nil, @(sendMyLocation));
}

// Debug capture, off unless this flag file exists (touch it over SSH, delete it when done):
//   /var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug
// Logs every Find My-related request (process, path, status, size) to fmf-requests.log and
// keeps the latest body of each friends reply, in .../com.findmy.livebridge.debug.d/.
static BOOL fmbDebugEnabled(void) {
    return [[NSFileManager defaultManager] fileExistsAtPath:
            resolvePath(@"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug",
                        @"/var/mobile/Library/Preferences/com.findmy.livebridge.debug")];
}

static void fmbDebugRecord(NSURLRequest *request, NSData *data, NSURLResponse *response, NSError *error, BOOL saveBody) {
    if (!fmbDebugEnabled()) return;
    NSString *dir = resolvePath(@"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d",
                                @"/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d");
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSString *proc = [NSString stringWithUTF8String:getprogname() ?: "?"];
    NSURL *url = request.URL;
    NSInteger status = [response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)response).statusCode : 0;
    NSString *line = [NSString stringWithFormat:@"%lld %@ %@ %@%@ status=%ld bytes=%lu%@\n",
                      currentEpochMs(), proc, request.HTTPMethod ?: @"GET", url.host ?: @"", url.path ?: @"",
                      (long)status, (unsigned long)data.length, error ? [@" error=" stringByAppendingString:error.localizedDescription] : @""];
    NSString *logPath = [dir stringByAppendingPathComponent:@"fmf-requests.log"];
    NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:logPath];
    if (!fh) {
        [[NSFileManager defaultManager] createFileAtPath:logPath contents:nil attributes:nil];
        fh = [NSFileHandle fileHandleForWritingAtPath:logPath];
    }
    [fh seekToEndOfFile];
    [fh writeData:[line dataUsingEncoding:NSUTF8StringEncoding]];
    [fh closeFile];
    if (saveBody && data.length > 0) {
        NSString *name = [NSString stringWithFormat:@"%@-%@.json", proc, url.lastPathComponent ?: @"response"];
        [data writeToFile:[dir stringByAppendingPathComponent:name] atomically:YES];
    }
}

// With the debug flag set, writes the Objective-C classes related to Find My friends / secure
// locations (names, superclasses, methods, properties; no data) to debug.d/<process>-classes.txt.
static BOOL fmbInterestingClass(NSString *n) {
    return [n hasPrefix:@"FMF"] || [n hasPrefix:@"FML"] || [n hasPrefix:@"FMCore"] ||
           [n containsString:@"SecureLocation"] || [n containsString:@"FriendLocation"] ||
           ([n hasPrefix:@"SP"] && ([n containsString:@"Location"] || [n containsString:@"Share"])) ||
           ([n hasPrefix:@"FM"] && ([n containsString:@"Friend"] || [n containsString:@"Location"] || [n containsString:@"Person"]));
}

static void fmbDumpClasses(void) {
    if (!fmbDebugEnabled()) return;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8 * NSEC_PER_SEC)), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        @autoreleasepool {
            unsigned int count = 0;
            Class *classes = objc_copyClassList(&count);
            NSMutableString *out = [NSMutableString string];
            for (unsigned int i = 0; i < count; i++) {
                Class c = classes[i];
                NSString *name = [NSString stringWithUTF8String:class_getName(c)];
                if (!fmbInterestingClass(name)) continue;
                Class sup = class_getSuperclass(c);
                [out appendFormat:@"@interface %@ : %@\n", name, sup ? [NSString stringWithUTF8String:class_getName(sup)] : @"-"];
                unsigned int pc = 0;
                objc_property_t *props = class_copyPropertyList(c, &pc);
                for (unsigned int j = 0; j < pc; j++) {
                    [out appendFormat:@"  @property %s  [%s]\n", property_getName(props[j]), property_getAttributes(props[j])];
                }
                free(props);
                for (int meta = 1; meta >= 0; meta--) {
                    unsigned int mc = 0;
                    Method *methods = class_copyMethodList(meta ? object_getClass(c) : c, &mc);
                    for (unsigned int j = 0; j < mc; j++) {
                        [out appendFormat:@"  %c %s  %s\n", meta ? '+' : '-', sel_getName(method_getName(methods[j])),
                                          method_getTypeEncoding(methods[j]) ?: ""];
                    }
                    free(methods);
                }
            }
            free(classes);
            NSString *dir = resolvePath(@"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d",
                                        @"/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d");
            [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
            NSString *path = [dir stringByAppendingPathComponent:
                              [NSString stringWithFormat:@"%s-classes.txt", getprogname() ?: "unknown"]];
            [out writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
        }
    });
}

static void fmbDebugLog(NSString *msg) {
    if (!fmbDebugEnabled()) return;
    NSString *dir = resolvePath(@"/var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d",
                                @"/var/mobile/Library/Preferences/com.findmy.livebridge.debug.d");
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSString *path = [dir stringByAppendingPathComponent:@"fmf-locations.log"];
    NSString *line = [NSString stringWithFormat:@"%lld %s %@\n", currentEpochMs(), getprogname() ?: "?", msg];
    NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:path];
    if (!fh) {
        [[NSFileManager defaultManager] createFileAtPath:path contents:nil attributes:nil];
        fh = [NSFileHandle fileHandleForWritingAtPath:path];
    }
    [fh seekToEndOfFile];
    [fh writeData:[line dataUsingEncoding:NSUTF8StringEncoding]];
    [fh closeFile];
}

static BOOL fmbIsFindMyURL(NSString *urlStr) {
    return [urlStr containsString:@"fmf"] || [urlStr containsString:@"fmip"] || [urlStr containsString:@"findmy"];
}

static NSURLSessionDataTask *(*orig_dataTaskWithRequest_completionHandler)(
    NSURLSession *, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *)
) = NULL;

static NSURLSessionDataTask *hooked_dataTaskWithRequest_completionHandler(
    NSURLSession *self,
    SEL _cmd,
    NSURLRequest *request,
    void (^completionHandler)(NSData *data, NSURLResponse *response, NSError *error)
) {
    NSString *urlStr = request.URL.absoluteString ?: @"";
    BOOL isFriends = [urlStr containsString:@"fmfmobile.icloud.com/fmipservice/friends/"];
    if (completionHandler && (isFriends || (fmbIsFindMyURL(urlStr) && fmbDebugEnabled()))) {
        void (^wrappedHandler)(NSData *, NSURLResponse *, NSError *) = ^(NSData *data, NSURLResponse *response, NSError *error) {
            fmbDebugRecord(request, data, response, error, isFriends);
            if (isFriends && data.length > 0 && !error) {
                NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
                if ([json isKindOfClass:[NSDictionary class]]) {
                    extractAndStoreFriendsFromFMFJson(json);
                }
            }
            completionHandler(data, response, error);
        };
        return orig_dataTaskWithRequest_completionHandler(self, _cmd, request, wrappedHandler);
    }
    return orig_dataTaskWithRequest_completionHandler(self, _cmd, request, completionHandler);
}

static void installHook(Class cls, SEL sel, IMP newImp, IMP *origOut) {
    if (!cls) return;
    Method m = class_getInstanceMethod(cls, sel);
    if (!m) return;
    IMP oldImp = method_setImplementation(m, newImp);
    if (origOut && *origOut == NULL) {
        *origOut = oldImp;
    }
}

// Floating "Bridge Settings" Pill inside FindMy.app for instant access alongside Settings.app
@interface FMBFloatingLauncher : NSObject
+ (void)attachWhenReady;
@end

@implementation FMBFloatingLauncher

+ (UIWindow *)activeKeyWindow {
    for (UIScene *scene in [UIApplication sharedApplication].connectedScenes) {
        if ([scene isKindOfClass:[UIWindowScene class]]) {
            UIWindowScene *ws = (UIWindowScene *)scene;
            for (UIWindow *w in ws.windows) {
                if (w.isKeyWindow) return w;
            }
            if (ws.windows.firstObject) return ws.windows.firstObject;
        }
    }
    return nil;
}

+ (void)attachWhenReady {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        UIWindow *win = [self activeKeyWindow];
        if (!win) return;

        UIButton *pill = [UIButton buttonWithType:UIButtonTypeSystem];
        pill.frame = CGRectMake(win.bounds.size.width - 132, 54, 118, 34);
        pill.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleBottomMargin;
        [pill setTitle:@"Ping Relay" forState:UIControlStateNormal];
        [pill setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
        pill.titleLabel.font = [UIFont systemFontOfSize:12.5 weight:UIFontWeightSemibold];
        pill.backgroundColor = [[UIColor systemBlueColor] colorWithAlphaComponent:0.90];
        pill.layer.cornerRadius = 17.0;
        pill.layer.shadowColor = [UIColor blackColor].CGColor;
        pill.layer.shadowOpacity = 0.25;
        pill.layer.shadowRadius = 4.0;
        pill.layer.shadowOffset = CGSizeMake(0, 2);
        [pill addTarget:self action:@selector(openBridgeSettings) forControlEvents:UIControlEventTouchUpInside];
        [win addSubview:pill];
    });
}

+ (void)openBridgeSettings {
    UIWindow *win = [self activeKeyWindow];
    UIViewController *top = win.rootViewController;
    while (top.presentedViewController) {
        top = top.presentedViewController;
    }
    FMBSettingsViewController *vc = [[FMBSettingsViewController alloc] init];
    UINavigationController *nav = [[UINavigationController alloc] initWithRootViewController:vc];
    [top presentViewController:nav animated:YES completion:nil];
}

@end

/** Hooks where decrypted friends' locations are delivered (FMF.framework). Safe to call repeatedly. */
static void fmbInstallFriendLocationHooks(void) {
    static BOOL dm = NO, session = NO, proxy = NO, slm = NO, slr = NO;
    Class c;
    if (!slm && (c = objc_getClass("SPSecureLocationsManager"))) {
        installHook(c, NSSelectorFromString(@"receivedUpdatedLocations:"), (IMP)hooked_SLM_receivedUpdatedLocations,
                    (IMP *)&orig_SLM_receivedUpdatedLocations);
        slm = YES;
    }
    if (!slr && (c = objc_getClass("SPSecureLocationsSubscriptionResult"))) {
        installHook(c, @selector(setLocations:), (IMP)hooked_SLR_setLocations, (IMP *)&orig_SLR_setLocations);
        installHook(c, @selector(initWithCoder:), (IMP)hooked_SLR_initWithCoder, (IMP *)&orig_SLR_initWithCoder);
        slr = YES;
    }
    if (!dm && (c = objc_getClass("FMFSessionDataManager"))) {
        installHook(c, @selector(setLocations:), (IMP)hooked_DM_setLocations, (IMP *)&orig_DM_setLocations);
        dm = YES;
    }
    if (!session && (c = objc_getClass("FMFSession"))) {
        installHook(c, NSSelectorFromString(@"didUpdateLocations:"), (IMP)hooked_S_didUpdateLocations, (IMP *)&orig_S_didUpdateLocations);
        session = YES;
    }
    if (!proxy && (c = objc_getClass("FMFSessionProxy"))) {
        installHook(c, NSSelectorFromString(@"didUpdateLocations:"), (IMP)hooked_SP_didUpdateLocations, (IMP *)&orig_SP_didUpdateLocations);
        proxy = YES;
    }
}

static void initFindMyClientHooks(BOOL isFindMyApp, BOOL isDeviceDaemon) {
    fmbDumpClasses();
    fmbInstallFriendLocationHooks();
    for (int64_t delay = 3; delay <= 30; delay *= 3) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delay * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
            fmbInstallFriendLocationHooks();
        });
    }
    gIsDeviceDaemon = isDeviceDaemon;
    if (isDeviceDaemon) {
        gDeviceHookEnabled = isDeviceLocationHookEnabled();
        int cfgToken = 0;
        notify_register_dispatch(kNotifyConfigChanged, &cfgToken, dispatch_get_main_queue(), ^(int token) {
            gDeviceHookEnabled = isDeviceLocationHookEnabled();
        });
        // When Android asks for a refresh, fetch friends' locations from searchpartyd ourselves,
        // rather than waiting for the Find My app. Only findmydeviced does this: it's an entitled
        // client, whereas searchpartyd is the server (it already sees locations via the hooks).
        const char *prog = getprogname();
        BOOL isFindMyDeviced = prog && strcmp(prog, "findmydeviced") == 0;
        if (isFindMyDeviced && objc_getClass("SPSecureLocationsManager")) {
            int refreshToken = 0;
            notify_register_dispatch(kNotifyRequestFriendsRefresh, &refreshToken,
                                     dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(int token) {
                fmbFetchSecureLocationsNow(@"android-refresh");
            });
        }
    }
    gManagersLock = [[NSObject alloc] init];
    gActiveManagers = [NSHashTable weakObjectsHashTable];
    gSwizzledDelegateClasses = [NSMutableSet set];

    Class clMgr = objc_getClass("CLLocationManager");
    installHook(clMgr, @selector(location), (IMP)hooked_CLLocationManager_location, (IMP *)&orig_CLLocationManager_location);
    installHook(clMgr, @selector(setDelegate:), (IMP)hooked_CLLocationManager_setDelegate, (IMP *)&orig_CLLocationManager_setDelegate);
    installHook(clMgr, @selector(startUpdatingLocation), (IMP)hooked_CLLocationManager_startUpdatingLocation, (IMP *)&orig_CLLocationManager_startUpdatingLocation);
    installHook(clMgr, @selector(requestLocation), (IMP)hooked_CLLocationManager_requestLocation, (IMP *)&orig_CLLocationManager_requestLocation);

    Class clSrcInfo = objc_getClass("CLLocationSourceInformation");
    if (clSrcInfo) {
        installHook(clSrcInfo, @selector(isSimulatedBySoftware), (IMP)hooked_isSimulatedBySoftware, (IMP *)&orig_isSimulatedBySoftware);
        installHook(clSrcInfo, @selector(isProducedByAccessory), (IMP)hooked_isProducedByAccessory, (IMP *)&orig_isProducedByAccessory);
    }

    Class urlSessionCls = objc_getClass("NSURLSession");
    installHook(
        urlSessionCls,
        @selector(dataTaskWithRequest:completionHandler:),
        (IMP)hooked_dataTaskWithRequest_completionHandler,
        (IMP *)&orig_dataTaskWithRequest_completionHandler
    );

    int notifyToken = 0;
    notify_register_dispatch(kNotifyLocationUpdated, &notifyToken, dispatch_get_main_queue(), ^(int token) {
        pushLiveLocationToActiveManagers();
    });

    if (isFindMyApp) {
        [FMBFloatingLauncher attachWhenReady];
    }
}

#pragma mark - Role 1: SpringBoard Network & E2EE Internet Relay Hub

// Replay protection state (see fmbAcceptSeq / fmbNextSeq in FMBShared.h). The LAN
// server and the relay poller run on different queues, so both go through gSeqLock.
static NSObject *gSeqLock = nil;
static int64_t gLastAcceptedUpSeq = 0;
static int64_t gLastSentDownSeq = 0;

static NSDictionary *buildDownlinkPayload(NSString *msgType) {
    NSDictionary *liveState = loadLiveState();
    NSString *friendsPath = resolvePath(kFriendsPathRootless, kFriendsPathFallback);
    NSData *friendsData = [NSData dataWithContentsOfFile:friendsPath];
    NSDictionary *friendsFile = friendsData ? [NSJSONSerialization JSONObjectWithData:friendsData options:0 error:nil] : nil;

    NSArray *friendsList = ([friendsFile isKindOfClass:[NSDictionary class]] && [friendsFile[@"friends"] isKindOfClass:[NSArray class]])
                           ? friendsFile[@"friends"]
                           : @[];
    BOOL meDeviceActive = ([friendsFile isKindOfClass:[NSDictionary class]] && friendsFile[@"meDeviceActive"] != nil)
                          ? [friendsFile[@"meDeviceActive"] boolValue]
                          : YES;

    int64_t seq;
    @synchronized (gSeqLock) {
        seq = fmbNextSeq(&gLastSentDownSeq);
    }

    return @{
        @"type": msgType ?: @"friends_state",
        @"seq": @(seq),
        @"relayTimeMs": @(currentEpochMs()),
        @"relayActive": @(isBridgeEnabled()),
        @"meDeviceActive": @(meDeviceActive),
        @"fmfdHooked": @YES,
        @"lastInjectedFixTimeMs": liveState[@"fixTimeMs"] ?: @0,
        @"liveFixActive": @(liveState != nil),
        @"friends": friendsList
    };
}

// ntfy.sh limits anonymous clients (~1 request / 5 s refill, 250 messages / day per IP), so
// unchanged friend lists aren't re-sent more than every 5 minutes, and a 429 pauses publishing.
static int64_t gRelayBlockedUntilMs = 0;
static NSString *gLastFriendsDigest = nil;
static int64_t gLastFriendsPublishMs = 0;
static const int64_t kFriendsRepublishMs = 5 * 60 * 1000;

static int64_t retryAfterMs(NSHTTPURLResponse *http) {
    NSString *ra = [http valueForHTTPHeaderField:@"Retry-After"];
    double secs = ra ? [ra doubleValue] : 0;
    return secs > 0 ? (int64_t)(secs * 1000) : 60 * 1000;
}

static void publishDownlinkToInternetRelay(NSString *msgType, BOOL force) {
    NSDictionary *cfg = loadOrCreateBridgeConfig();
    if (!cfg) return;
    int64_t nowMs = currentEpochMs();
    @synchronized (gSeqLock) {
        if (nowMs < gRelayBlockedUntilMs) return;
    }
    NSString *relayUrl  = cfg[@"relayUrl"];
    NSString *channelId = cfg[@"channelId"];
    NSData *keyData     = [[NSData alloc] initWithBase64EncodedString:cfg[@"keyBase64"] options:0];
    if (!relayUrl || !channelId || keyData.length != 32) return;

    NSDictionary *downPayload = buildDownlinkPayload(msgType);
    BOOL isFriends = [msgType isEqualToString:@"friends_state"];
    NSString *digest = nil;
    if (isFriends) {
        NSData *d = [NSJSONSerialization dataWithJSONObject:downPayload[@"friends"] ?: @[]
                                                    options:NSJSONWritingSortedKeys error:nil];
        digest = d ? [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] : @"";
        @synchronized (gSeqLock) {
            if (!force && [digest isEqualToString:gLastFriendsDigest] && nowMs - gLastFriendsPublishMs < kFriendsRepublishMs) {
                return;
            }
        }
    }
    NSString *envJson = encryptEnvelopeJson(downPayload, @"down", channelId, keyData);
    if (!envJson) return;

    NSString *downUrlStr = [NSString stringWithFormat:@"%@/fmb-%@-down", relayUrl, channelId];
    NSURL *url = [NSURL URLWithString:downUrlStr];
    if (!url) return;

    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
    req.HTTPMethod = @"POST";
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    addRelayAuth(req, cfg);
    req.HTTPBody = [envJson dataUsingEncoding:NSUTF8StringEncoding];

    NSURLSessionDataTask *task = [[NSURLSession sharedSession] dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *err) {
        NSHTTPURLResponse *http = [resp isKindOfClass:[NSHTTPURLResponse class]] ? (NSHTTPURLResponse *)resp : nil;
        @synchronized (gSeqLock) {
            if (http.statusCode == 429) {
                gRelayBlockedUntilMs = currentEpochMs() + retryAfterMs(http);
            } else if (isFriends && http.statusCode >= 200 && http.statusCode < 300) {
                gLastFriendsDigest = digest;
                gLastFriendsPublishMs = currentEpochMs();
            }
        }
    }];
    [task resume];
}

static void handleIncomingEncryptedEnvelopeString(NSString *rawString, NSString **responseEnvelopeOut) {
    NSDictionary *cfg = loadOrCreateBridgeConfig();
    if (!cfg) return;
    NSString *channelId = cfg[@"channelId"];
    NSData *keyData     = [[NSData alloc] initWithBase64EncodedString:cfg[@"keyBase64"] options:0];
    if (!channelId || keyData.length != 32) return;

    NSData *rawData = [rawString dataUsingEncoding:NSUTF8StringEncoding];
    if (!rawData) return;
    NSDictionary *outer = [NSJSONSerialization JSONObjectWithData:rawData options:0 error:nil];
    if (![outer isKindOfClass:[NSDictionary class]]) return;

    NSDictionary *envDict = outer;
    if ([outer[@"message"] isKindOfClass:[NSString class]] && outer[@"ct"] == nil) {
        NSData *innerData = [outer[@"message"] dataUsingEncoding:NSUTF8StringEncoding];
        if (innerData) {
            NSDictionary *parsedInner = [NSJSONSerialization JSONObjectWithData:innerData options:0 error:nil];
            if ([parsedInner isKindOfClass:[NSDictionary class]]) {
                envDict = parsedInner;
            }
        }
    }

    NSDictionary *payload = decryptEnvelopeJson(envDict, @"up", channelId, keyData);
    if (!payload) return;

    BOOL fresh;
    @synchronized (gSeqLock) {
        fresh = fmbAcceptSeq(&gLastAcceptedUpSeq, payload, kMaxUplinkAgeMs, NULL);
    }
    if (!fresh) return;

    NSString *type = payload[@"type"] ?: @"location_update";
    if ([type isEqualToString:@"location_update"] && isBridgeEnabled()) {
        NSString *reason = nil;
        validateAndStoreIncomingFix(payload, &reason);
    }

    if ([payload[@"requestFriendsRefresh"] boolValue] || [type isEqualToString:@"refresh_request"]) {
        notify_post(kNotifyRequestFriendsRefresh);
        publishDownlinkToInternetRelay(@"friends_state", YES);
    }

    if (responseEnvelopeOut) {
        NSDictionary *downPayload = buildDownlinkPayload(@"friends_state");
        *responseEnvelopeOut = encryptEnvelopeJson(downPayload, @"down", channelId, keyData);
    }
}

// Streams the uplink topic over one long-lived connection (ntfy JSON stream; keepalives every
// ~45 s) instead of polling, reconnecting with exponential backoff or the relay's Retry-After.
static int64_t gLastSeenUpTimeSec = 0;
static NSURLSessionDataTask *gStreamTask = nil;

@interface FMBRelayStream : NSObject <NSURLSessionDataDelegate>
@property (nonatomic, strong) NSMutableData *buffer;
@property (nonatomic, strong) dispatch_semaphore_t done;
@property (nonatomic, assign) NSInteger status;
@property (nonatomic, assign) int64_t retryAfter;
@end

@implementation FMBRelayStream
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveResponse:(NSURLResponse *)response
     completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
    self.status = http.statusCode;
    if (http.statusCode == 429) self.retryAfter = retryAfterMs(http);
    completionHandler(http.statusCode >= 200 && http.statusCode < 300
                      ? NSURLSessionResponseAllow : NSURLSessionResponseCancel);
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    [self.buffer appendData:data];
    NSData *newline = [NSData dataWithBytes:"\n" length:1];
    while (true) {
        NSRange r = [self.buffer rangeOfData:newline options:0 range:NSMakeRange(0, self.buffer.length)];
        if (r.location == NSNotFound) break;
        NSData *lineData = [self.buffer subdataWithRange:NSMakeRange(0, r.location)];
        [self.buffer replaceBytesInRange:NSMakeRange(0, r.location + 1) withBytes:NULL length:0];
        NSString *line = [[NSString alloc] initWithData:lineData encoding:NSUTF8StringEncoding];
        if (line.length < 2) continue;
        NSDictionary *evt = [NSJSONSerialization JSONObjectWithData:lineData options:0 error:nil];
        if (![evt isKindOfClass:[NSDictionary class]]) continue;
        NSString *event = evt[@"event"];
        if ([event isKindOfClass:[NSString class]] && ![event isEqualToString:@"message"]) continue; // open / keepalive
        int64_t t = [evt[@"time"] longLongValue];
        if (t >= gLastSeenUpTimeSec) gLastSeenUpTimeSec = t + 1;
        handleIncomingEncryptedEnvelopeString(line, NULL);
    }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    dispatch_semaphore_signal(self.done);
}
@end

static void startInternetRelayStream(void) {
    // A pairing/relay change drops the current connection so the loop reconnects with it.
    int cfgToken = 0;
    notify_register_dispatch(kNotifyConfigChanged, &cfgToken, dispatch_get_main_queue(), ^(int token) {
        [gStreamTask cancel];
    });

    dispatch_queue_t q = dispatch_queue_create("com.findmy.livebridge.relaystream", DISPATCH_QUEUE_SERIAL);
    dispatch_async(q, ^{
        double backoff = 5.0;
        while (true) {
            @autoreleasepool {
                NSDictionary *cfg = loadOrCreateBridgeConfig();
                NSString *relayUrl  = cfg[@"relayUrl"];
                NSString *channelId = cfg[@"channelId"];
                NSString *since = gLastSeenUpTimeSec > 0 ? [NSString stringWithFormat:@"%lld", gLastSeenUpTimeSec] : @"30s";
                NSURL *url = (relayUrl && channelId)
                    ? [NSURL URLWithString:[NSString stringWithFormat:@"%@/fmb-%@-up/json?since=%@", relayUrl, channelId, since]]
                    : nil;
                if (!url) {
                    [NSThread sleepForTimeInterval:10.0];
                    continue;
                }

                NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
                addRelayAuth(req, cfg);
                NSURLSessionConfiguration *conf = [NSURLSessionConfiguration ephemeralSessionConfiguration];
                conf.timeoutIntervalForRequest = 100;          // idle timeout; ntfy keepalives every ~45 s
                conf.timeoutIntervalForResource = 24 * 3600;

                FMBRelayStream *stream = [FMBRelayStream new];
                stream.buffer = [NSMutableData data];
                stream.done = dispatch_semaphore_create(0);
                NSURLSession *session = [NSURLSession sessionWithConfiguration:conf delegate:stream delegateQueue:nil];
                NSURLSessionDataTask *task = [session dataTaskWithRequest:req];
                gStreamTask = task;
                [task resume];
                dispatch_semaphore_wait(stream.done, DISPATCH_TIME_FOREVER);
                gStreamTask = nil;
                [session finishTasksAndInvalidate];

                double wait;
                if (stream.status >= 200 && stream.status < 300) {
                    backoff = 5.0;
                    wait = 2.0; // dropped after a healthy connection: reconnect promptly
                } else {
                    wait = stream.retryAfter > 0 ? stream.retryAfter / 1000.0 : backoff;
                    backoff = MIN(backoff * 2.0, 300.0);
                }
                [NSThread sleepForTimeInterval:wait];
            }
        }
    });
}

// Reads one HTTP request: headers, then exactly Content-Length body bytes, capped at
// kMaxRequestBytes and bounded by the socket's receive timeout. Returns nil on error.
static const size_t kMaxRequestBytes = 64 * 1024;

static NSData *readHttpRequest(int fd, NSString **headersOut) {
    NSMutableData *buf = [NSMutableData data];
    NSData *sep = [@"\r\n\r\n" dataUsingEncoding:NSASCIIStringEncoding];
    NSUInteger headerEnd = NSNotFound;
    size_t contentLength = 0;
    char chunk[4096];

    while (buf.length < kMaxRequestBytes) {
        ssize_t n = recv(fd, chunk, sizeof(chunk), 0);
        if (n <= 0) return nil; // closed, error or timeout
        [buf appendBytes:chunk length:(NSUInteger)n];

        if (headerEnd == NSNotFound) {
            NSRange r = [buf rangeOfData:sep options:0 range:NSMakeRange(0, buf.length)];
            if (r.location == NSNotFound) continue;
            headerEnd = r.location + r.length;
            NSString *headers = [[NSString alloc] initWithData:[buf subdataWithRange:NSMakeRange(0, r.location)]
                                                      encoding:NSUTF8StringEncoding];
            if (!headers) return nil;
            for (NSString *line in [headers componentsSeparatedByString:@"\r\n"]) {
                if ([line.lowercaseString hasPrefix:@"content-length:"]) {
                    long long len = [[line substringFromIndex:15] longLongValue];
                    if (len < 0 || (unsigned long long)len > kMaxRequestBytes) return nil;
                    contentLength = (size_t)len;
                }
            }
            if (headersOut) *headersOut = headers;
        }
        if (headerEnd != NSNotFound && buf.length >= headerEnd + contentLength) {
            return [buf subdataWithRange:NSMakeRange(headerEnd, contentLength)];
        }
    }
    return nil;
}

static void sendAll(int fd, NSData *data) {
    const uint8_t *p = data.bytes;
    size_t left = data.length;
    while (left > 0) {
        ssize_t n = send(fd, p, left, 0);
        if (n <= 0) return;
        p += n;
        left -= (size_t)n;
    }
}

static void startLocalHttpServer(void) {
    dispatch_queue_t serverQueue = dispatch_queue_create("com.findmy.livebridge.http", DISPATCH_QUEUE_CONCURRENT);
    dispatch_async(serverQueue, ^{
        int serverFd = socket(AF_INET, SOCK_STREAM, 0);
        if (serverFd < 0) return;

        int opt = 1;
        setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = htons(kDefaultHttpPort);

        if (bind(serverFd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            close(serverFd);
            return;
        }
        if (listen(serverFd, 16) < 0) {
            close(serverFd);
            return;
        }

        while (true) {
            struct sockaddr_in clientAddr;
            socklen_t clientLen = sizeof(clientAddr);
            int clientFd = accept(serverFd, (struct sockaddr *)&clientAddr, &clientLen);
            if (clientFd < 0) continue;

            BOOL isLoopback = (ntohl(clientAddr.sin_addr.s_addr) == INADDR_LOOPBACK);

            struct timeval tv = { .tv_sec = 5, .tv_usec = 0 };
            setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
            setsockopt(clientFd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
            int noSigpipe = 1;
            setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &noSigpipe, sizeof(noSigpipe));

            dispatch_async(serverQueue, ^{
                @autoreleasepool {
                    NSString *headers = nil;
                    NSData *bodyData = readHttpRequest(clientFd, &headers);

                    NSString *respBody = @"{\"ok\":true,\"service\":\"PingRelay\",\"version\":\"1.4.8\"}";
                    NSString *contentType = @"application/json";
                    int statusCode = 200;
                    NSString *body = bodyData ? [[NSString alloc] initWithData:bodyData encoding:NSUTF8StringEncoding] : nil;

                    if (!bodyData || !headers || !body) {
                        statusCode = 400;
                        respBody = @"{\"ok\":false,\"error\":\"bad_request\"}";
                    } else if ([headers hasPrefix:@"POST /v1/sync "]) {
                        NSString *downEnv = nil;
                        handleIncomingEncryptedEnvelopeString(body, &downEnv);
                        if (downEnv) {
                            respBody = downEnv;
                        } else {
                            statusCode = 401;
                            respBody = @"{\"ok\":false,\"error\":\"invalid_or_unauthorized_envelope\"}";
                        }
                    } else if ([headers hasPrefix:@"GET /v1/pairing "] && isLoopback) {
                        NSDictionary *cfg = loadOrCreateBridgeConfig();
                        respBody = cfg[@"pairingCode"] ?: @"";
                        contentType = @"text/plain; charset=utf-8";
                    } else if ([headers hasPrefix:@"POST /v1/pairing "] && isLoopback) {
                        NSDictionary *parsed = parsePairingCode(body);
                        if (parsed) {
                            saveBridgeConfigDictionary(parsed);
                            respBody = @"{\"ok\":true,\"paired\":true}";
                        } else {
                            statusCode = 400;
                            respBody = @"{\"ok\":false,\"error\":\"invalid_pairing_code\"}";
                        }
                    }

                    NSString *reason = statusCode == 200 ? @"OK" : (statusCode == 401 ? @"Unauthorized" : @"Bad Request");
                    NSData *respData = [respBody dataUsingEncoding:NSUTF8StringEncoding];
                    NSString *header = [NSString stringWithFormat:
                        @"HTTP/1.1 %d %@\r\nContent-Type: %@\r\nContent-Length: %lu\r\nConnection: close\r\n\r\n",
                        statusCode, reason, contentType, (unsigned long)respData.length];
                    sendAll(clientFd, [header dataUsingEncoding:NSUTF8StringEncoding]);
                    sendAll(clientFd, respData);
                    close(clientFd);
                }
            });
        }
    });
}

static void initSpringBoardHub(void) {
    gSeqLock = [[NSObject alloc] init];
    loadOrCreateBridgeConfig();
    startLocalHttpServer();
    startInternetRelayStream();

    int friendsToken = 0;
    notify_register_dispatch(kNotifyFriendsUpdated, &friendsToken, dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^(int token) {
        publishDownlinkToInternetRelay(@"friends_state", NO);
    });

    int locateToken = 0;
    notify_register_dispatch(kNotifyLocateNeeded, &locateToken, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(int token) {
        publishDownlinkToInternetRelay(@"locate_request", YES);
    });
}

#pragma mark - Tweak Constructor (%ctor)

__attribute__((constructor))
static void FindMyLiveBridgeInit(void) {
    @autoreleasepool {
        const char *prog = getprogname();
        if (!prog) return;
        NSString *procName = [NSString stringWithUTF8String:prog];

        if ([procName isEqualToString:@"SpringBoard"]) {
            initSpringBoardHub();
        } else if ([procName isEqualToString:@"FindMy"]) {
            initFindMyClientHooks(YES, NO);
        } else if ([procName isEqualToString:@"fmfd"]) {
            initFindMyClientHooks(NO, NO);
        } else if ([procName isEqualToString:@"findmydeviced"] ||
                   [procName isEqualToString:@"searchpartyd"]) {
            initFindMyClientHooks(NO, YES);
        }
    }
}
