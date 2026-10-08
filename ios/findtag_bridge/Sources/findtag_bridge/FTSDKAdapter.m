#import "FTSDKAdapter.h"
#import <TagSdk/TagSdk-Swift.h>

static NSDictionary *FTErrorMap(TagError *error) {
    if (error == nil) return nil;
    return @{
        @"code": error.code ?: @"unknown",
        @"message": error.message ?: @"FindTag işlemi başarısız oldu",
        @"serverCode": error.serverCode ?: [NSNull null]
    };
}

@interface FTBindingHandler : NSObject <CustomerBindingHandler>
@property(nonatomic, copy) FTBindingPersistBlock persister;
@property(nonatomic, copy) NSDictionary *metadata;
@end

@implementation FTBindingHandler
- (void)bind:(TagCustomerBindingRequest *)request
  completion:(id<TagCustomerBindingCompletion>)completion {
    NSString *key = request.bindingInfo.primaryDeviceKey.deviceKey;
    self.persister(key, self.metadata ?: @{}, ^(BOOL approved) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (approved) {
                [completion complete:[TagCustomerBindingDecision approved]];
            } else {
                TagCustomerBindingError *error = [[TagCustomerBindingError alloc]
                    initWithMessage:@"Cihaz bu telefona kaydedilemedi" platformCause:nil];
                [completion complete:[TagCustomerBindingDecision rejected:error]];
            }
        });
    });
}
@end

@interface FTScanListener : NSObject <TagScanListener>
@property(nonatomic, weak) FTSDKAdapter *owner;
@end

@interface FTBluetoothListener : NSObject <TagBluetoothStateListener>
@property(nonatomic, weak) FTSDKAdapter *owner;
@end

@interface FTSDKAdapter ()
@property(nonatomic, strong) NSMutableDictionary<NSString *, TagDevice *> *devices;
@property(nonatomic, strong) FTBindingHandler *bindingHandler;
@property(nonatomic, strong) FTScanListener *scanListener;
@property(nonatomic, strong) FTBluetoothListener *bluetoothListener;
@property(nonatomic, strong, nullable) id<TagSubscription> scanSubscription;
@property(nonatomic, strong, nullable) id<TagSubscription> bluetoothSubscription;
@end

@implementation FTSDKAdapter
- (instancetype)init {
    self = [super init];
    if (self) {
        _devices = [NSMutableDictionary dictionary];
        _scanListener = [FTScanListener new];
        _scanListener.owner = self;
        _bluetoothListener = [FTBluetoothListener new];
        _bluetoothListener.owner = self;
    }
    return self;
}

- (NSDictionary *)initializeWithApiKey:(NSString *)apiKey
                              apiSecret:(NSString *)apiSecret
                        bindingPersister:(FTBindingPersistBlock)persister {
    self.bindingHandler = [FTBindingHandler new];
    self.bindingHandler.persister = persister;
    TagOpenApiCredential *credential = nil;
    if (apiKey.length > 0 && apiSecret.length > 0) {
        credential = [[TagOpenApiCredential alloc] initWithApiKey:apiKey apiSecret:apiSecret];
    }
    TagSdkConfig *config = [[TagSdkConfig alloc]
        initWithIntegrationMode:TagIntegrationModeCustomerManaged
        openApiCredential:credential
        customerBindingTimeoutMs:30000
        customerBindingHandler:self.bindingHandler
        logEnabled:YES];
    return FTErrorMap([TagSdk initializeWithConfig:config]);
}

- (NSString *)sdkVersion { return [TagSdk getSdkVersion]; }

- (void)observeBluetooth {
    if (self.bluetoothSubscription == nil) {
        self.bluetoothSubscription = [TagSdk observeBluetoothState:self.bluetoothListener];
    }
}

- (void)startScanWithTimeoutMs:(int64_t)timeoutMs {
    [self.scanSubscription cancel];
    [self.devices removeAllObjects];
    TagScanOptions *options = [[TagScanOptions alloc] initWithTimeoutMs:timeoutMs];
    self.scanSubscription = [TagSdk startScanWithOptions:options listener:self.scanListener];
}

- (void)stopScan { [TagSdk stopScan]; }

- (void)bindScanId:(NSString *)scanId completion:(FTValueBlock)completion {
    TagDevice *device = self.devices[scanId];
    if (device == nil) {
        completion(nil, @{@"code": @"staleScanDevice", @"message": @"Tarama sonucu artık geçerli değil; yeniden tarayın."});
        return;
    }
    self.bindingHandler.metadata = @{
        @"name": device.name ?: [NSNull null],
        @"platformAddress": device.platformAddress ?: [NSNull null],
        @"advertisedMac": device.advertisedMac ?: [NSNull null]
    };
    [TagSdk connectAndBindDevice:device completion:^(TagBindingInfo *info, TagError *error) {
        completion(info.primaryDeviceKey.deviceKey, FTErrorMap(error));
    }];
}

- (void)findScanId:(NSString *)scanId completion:(FTValueBlock)completion {
    TagDevice *device = self.devices[scanId];
    if (device == nil) {
        completion(nil, @{@"code": @"staleScanDevice", @"message": @"Cihazı sesle bulmak için yeniden tarayın."});
        return;
    }
    [TagSdk findDevice:device completion:^(FindDeviceResult *value, TagError *error) {
        completion(value == nil ? nil : @{
            @"triggered": @(value.triggered),
            @"rawBattery": value.battery == nil ? [NSNull null] : @(value.battery.rawBattery),
            @"serverBatteryLevel": value.battery == nil ? [NSNull null] : @(value.battery.serverLevel)
        }, FTErrorMap(error));
    }];
}

- (void)getDataForDeviceKey:(NSString *)deviceKey
                     preset:(NSString *)preset
                startTimeMs:(NSNumber *)startTimeMs
                  endTimeMs:(NSNumber *)endTimeMs
                 completion:(FTValueBlock)completion {
    TagDeviceDataPreset selectedPreset = TagDeviceDataPresetLatest;
    if ([preset isEqualToString:@"last1Hour"]) selectedPreset = TagDeviceDataPresetLast1Hour;
    else if ([preset isEqualToString:@"last2Hours"]) selectedPreset = TagDeviceDataPresetLast2Hours;
    else if ([preset isEqualToString:@"last4Hours"]) selectedPreset = TagDeviceDataPresetLast4Hours;
    else if ([preset isEqualToString:@"last6Hours"]) selectedPreset = TagDeviceDataPresetLast6Hours;
    else if ([preset isEqualToString:@"custom"]) selectedPreset = TagDeviceDataPresetCustom;
    else if (![preset isEqualToString:@"latest"]) {
        completion(nil, @{@"code": @"invalidArgument", @"message": @"Desteklenmeyen konum aralığı."});
        return;
    }
    // SDK kuralı: özel aralıkta iki uç da zorunlu ve başlangıç bitişten sonra
    // olamaz; hazır aralıklar uç taşımaz.
    BOOL custom = selectedPreset == TagDeviceDataPresetCustom;
    if (custom && (startTimeMs == nil || endTimeMs == nil ||
                   startTimeMs.longLongValue > endTimeMs.longLongValue)) {
        completion(nil, @{@"code": @"invalidArgument",
                          @"message": @"Özel aralık için geçerli başlangıç ve bitiş zamanı gerekir."});
        return;
    }
    TagDeviceKey *key = [[TagDeviceKey alloc] initWithDeviceKey:deviceKey];
    TagDeviceDataQuery *query = [[TagDeviceDataQuery alloc]
        initWithDeviceKey:key
                   preset:selectedPreset
              startTimeMs:custom ? startTimeMs : nil
                endTimeMs:custom ? endTimeMs : nil];
    [TagSdk getDeviceData:query completion:^(NSArray<TagDeviceData *> *rows, TagError *error) {
        if (error != nil) { completion(nil, FTErrorMap(error)); return; }
        NSMutableArray *mapped = [NSMutableArray array];
        for (TagDeviceData *row in rows ?: @[]) {
            [mapped addObject:@{
                @"collectionTimeMs": @(row.collectionTimeMs),
                @"longitude": @(row.longitude), @"latitude": @(row.latitude),
                @"batteryLevel": row.batteryLevel ?: [NSNull null],
                @"accuracyLevel": row.accuracyLevel ?: [NSNull null],
                @"googleLocation": @(row.googleLocation)
            }];
        }
        completion(mapped, nil);
    }];
}

- (void)exportLogsWithCompletion:(FTValueBlock)completion {
    [TagSdk exportLogs:^(TagLogArchive *archive, TagError *error) {
        if (error != nil) { completion(nil, FTErrorMap(error)); return; }
        if (archive == nil) {
            completion(nil, @{@"code": @"logExportFailed", @"message": @"SDK log arşivi oluşturulamadı."});
            return;
        }
        completion(@{
            @"filePath": archive.filePath,
            @"fileSizeBytes": @(archive.fileSizeBytes),
            @"createdAtMs": @(archive.createdAtMs)
        }, nil);
    }];
}

- (void)releaseSdk {
    [self.scanSubscription cancel]; self.scanSubscription = nil;
    [self.bluetoothSubscription cancel]; self.bluetoothSubscription = nil;
    [self.devices removeAllObjects];
    [TagSdk releaseSdk];
}
@end

@implementation FTScanListener
- (void)onDeviceFound:(TagDevice *)device {
    self.owner.devices[device.id] = device;
    if (self.owner.scanDeviceEvent) {
        self.owner.scanDeviceEvent(@{
            @"id": device.id, @"name": device.name ?: [NSNull null], @"rssi": @(device.rssi),
            @"platformAddress": device.platformAddress ?: [NSNull null],
            @"advertisedMac": device.advertisedMac ?: [NSNull null]
        });
    }
}
- (void)onScanFinished:(TagScanFinishReason)reason {
    NSString *value = reason == TagScanFinishReasonTimeout ? @"timeout" :
        (reason == TagScanFinishReasonStopped ? @"stopped" : @"operationStarted");
    if (self.owner.scanFinishedEvent) self.owner.scanFinishedEvent(value);
}
- (void)onScanFailed:(TagError *)error {
    if (self.owner.scanErrorEvent) self.owner.scanErrorEvent(FTErrorMap(error));
}
@end

@implementation FTBluetoothListener
- (void)onStateChanged:(TagBluetoothState)state {
    NSString *value = state == TagBluetoothStatePoweredOn ? @"poweredOn" :
        (state == TagBluetoothStatePoweredOff ? @"poweredOff" :
         (state == TagBluetoothStateUnauthorized ? @"unauthorized" : @"unknown"));
    if (self.owner.bluetoothEvent) self.owner.bluetoothEvent(value);
}
@end
