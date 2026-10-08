#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^FTBindingPersistBlock)(NSString *deviceKey,
                                      NSDictionary<NSString *, id> *metadata,
                                      void (^decision)(BOOL approved));
typedef void (^FTValueBlock)(id _Nullable value, NSDictionary * _Nullable error);

@interface FTSDKAdapter : NSObject

@property(nonatomic, copy, nullable) void (^bluetoothEvent)(NSString *state);
@property(nonatomic, copy, nullable) void (^scanDeviceEvent)(NSDictionary *device);
@property(nonatomic, copy, nullable) void (^scanFinishedEvent)(NSString *reason);
@property(nonatomic, copy, nullable) void (^scanErrorEvent)(NSDictionary *error);

- (NSDictionary * _Nullable)initializeWithApiKey:(NSString * _Nullable)apiKey
                                        apiSecret:(NSString * _Nullable)apiSecret
                                  bindingPersister:(FTBindingPersistBlock)persister;
- (NSString *)sdkVersion;
- (void)observeBluetooth;
- (void)startScanWithTimeoutMs:(int64_t)timeoutMs;
- (void)stopScan;
- (void)bindScanId:(NSString *)scanId completion:(FTValueBlock)completion;
- (void)findScanId:(NSString *)scanId completion:(FTValueBlock)completion;
- (void)getDataForDeviceKey:(NSString *)deviceKey
                     preset:(NSString *)preset
                startTimeMs:(nullable NSNumber *)startTimeMs
                  endTimeMs:(nullable NSNumber *)endTimeMs
                 completion:(FTValueBlock)completion;
- (void)exportLogsWithCompletion:(FTValueBlock)completion;
- (void)releaseSdk;

@end

NS_ASSUME_NONNULL_END
