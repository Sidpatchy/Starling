#import <Foundation/Foundation.h>
#include <stddef.h>
#include <stdint.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSString * _Nullable StarlingPythonInitialize(
    NSString *pythonHome,
    NSString *modulesPath,
    NSString *executablePath
);

FOUNDATION_EXPORT NSString *StarlingPythonInvoke(NSString *command);

@protocol StarlingBLEProvider <NSObject>
- (NSDictionary<NSString *, id> *)performBLEOperation:(NSString *)operation
                                            arguments:(NSArray<NSData *> *)arguments;
@end

typedef void (*StarlingBLEFreeBuffer)(uint8_t *buffer);
typedef int (*StarlingBLEStringCallback)(const uint8_t *value, size_t value_length,
                                         uint8_t **error, size_t *error_length);
typedef int (*StarlingBLEIdentityCallback)(const uint8_t *identity, size_t identity_length,
                                           uint8_t **error, size_t *error_length);
typedef int (*StarlingBLEBytesCallback)(const uint8_t *address, size_t address_length,
                                        const uint8_t *bytes, size_t bytes_length,
                                        uint8_t **error, size_t *error_length);
typedef int (*StarlingBLEVoidCallback)(uint8_t **error, size_t *error_length);
typedef int (*StarlingBLEDrainCallback)(uint8_t **json, size_t *json_length,
                                        uint8_t **error, size_t *error_length);

typedef struct {
    StarlingBLEStringCallback start;
    StarlingBLEIdentityCallback set_identity;
    StarlingBLEVoidCallback stop;
    StarlingBLEVoidCallback start_scanning;
    StarlingBLEVoidCallback stop_scanning;
    StarlingBLEVoidCallback start_advertising;
    StarlingBLEVoidCallback stop_advertising;
    StarlingBLEStringCallback connect;
    StarlingBLEStringCallback disconnect;
    StarlingBLEBytesCallback write;
    StarlingBLEBytesCallback notify;
    StarlingBLEDrainCallback drain_events;
    StarlingBLEFreeBuffer free_buffer;
} StarlingBLECallbacks;

FOUNDATION_EXPORT void StarlingBLERegisterCallbacks(const StarlingBLECallbacks * _Nullable callbacks);
FOUNDATION_EXPORT void StarlingBLERegisterProvider(id<StarlingBLEProvider> _Nullable provider);

NS_ASSUME_NONNULL_END
