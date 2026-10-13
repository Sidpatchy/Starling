#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSString * _Nullable StarlingPythonInitialize(
    NSString *pythonHome,
    NSString *modulesPath,
    NSString *executablePath
);

FOUNDATION_EXPORT NSString *StarlingPythonInvoke(NSString *command);

NS_ASSUME_NONNULL_END
