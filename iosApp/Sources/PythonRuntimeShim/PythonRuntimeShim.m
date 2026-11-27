#import "PythonRuntimeShim.h"
#import <Python/Python.h>
#import <dispatch/dispatch.h>
#include <string.h>
#include <stdlib.h>

static NSRecursiveLock *runtimeLock;
// Python background threads enter BLE callbacks with the GIL held. They must
// never acquire runtimeLock: an invocation holds it while Python may release
// and reacquire the GIL. Protect only the callback registry with this lock.
static NSLock *bleRegistryLock;
static PyObject *runtimeDispatcher;
static BOOL runtimeInitialized;
static StarlingBLECallbacks bleCallbacks;
static id<StarlingBLEProvider> bleProvider;
static void EnsureRuntimeLock(void);

static void BLEFreeBuffer(uint8_t *buffer) { free(buffer); }
static int BLEProviderCall(NSString *operation, const uint8_t *first, size_t firstLength,
                           const uint8_t *second, size_t secondLength,
                           uint8_t **output, size_t *outputLength,
                           uint8_t **error, size_t *errorLength) {
    EnsureRuntimeLock();
    [bleRegistryLock lock];
    id<StarlingBLEProvider> provider = bleProvider;
    [bleRegistryLock unlock];
    if (provider == nil) {
        const char *message = "iOS BLE callbacks are not registered";
        size_t length = strlen(message);
        *error = malloc(length);
        if (*error != NULL) { memcpy(*error, message, length); *errorLength = length; }
        return 0;
    }
    NSMutableArray<NSData *> *arguments = [NSMutableArray arrayWithCapacity:2];
    if (first != NULL || firstLength == 0) [arguments addObject:[NSData dataWithBytes:first length:firstLength]];
    if (second != NULL || secondLength == 0) [arguments addObject:[NSData dataWithBytes:second length:secondLength]];
    NSDictionary *result = [provider performBLEOperation:operation arguments:arguments];
    NSString *message = result[@"error"];
    if (message != nil) {
        NSData *data = [message dataUsingEncoding:NSUTF8StringEncoding];
        *error = malloc(data.length);
        if (*error != NULL) { memcpy(*error, data.bytes, data.length); *errorLength = data.length; }
        return 0;
    }
    NSData *data = result[@"data"];
    if (output != NULL) {
        *outputLength = data.length;
        *output = malloc(MAX((NSUInteger)1, data.length));
        if (*output == NULL) return 0;
        if (data.length != 0) memcpy(*output, data.bytes, data.length);
    }
    return 1;
}
static int BLEStartProvider(const uint8_t *p,size_t n,uint8_t **e,size_t *en){return BLEProviderCall(@"start",p,n,NULL,0,NULL,NULL,e,en);}
static int BLEIdentityProvider(const uint8_t *p,size_t n,uint8_t **e,size_t *en){return BLEProviderCall(@"set_identity",p,n,NULL,0,NULL,NULL,e,en);}
static int BLEStopProvider(uint8_t **e,size_t *en){return BLEProviderCall(@"stop",NULL,0,NULL,0,NULL,NULL,e,en);}
static int BLEStartScanningProvider(uint8_t **e,size_t *en){return BLEProviderCall(@"start_scanning",NULL,0,NULL,0,NULL,NULL,e,en);}
static int BLEStopScanningProvider(uint8_t **e,size_t *en){return BLEProviderCall(@"stop_scanning",NULL,0,NULL,0,NULL,NULL,e,en);}
static int BLEStartAdvertisingProvider(uint8_t **e,size_t *en){return BLEProviderCall(@"start_advertising",NULL,0,NULL,0,NULL,NULL,e,en);}
static int BLEStopAdvertisingProvider(uint8_t **e,size_t *en){return BLEProviderCall(@"stop_advertising",NULL,0,NULL,0,NULL,NULL,e,en);}
static int BLEConnectProvider(const uint8_t *p,size_t n,uint8_t **e,size_t *en){return BLEProviderCall(@"connect",p,n,NULL,0,NULL,NULL,e,en);}
static int BLEDisconnectProvider(const uint8_t *p,size_t n,uint8_t **e,size_t *en){return BLEProviderCall(@"disconnect",p,n,NULL,0,NULL,NULL,e,en);}
static int BLEWriteProvider(const uint8_t *a,size_t an,const uint8_t *b,size_t bn,uint8_t **e,size_t *en){return BLEProviderCall(@"write",a,an,b,bn,NULL,NULL,e,en);}
static int BLENotifyProvider(const uint8_t *a,size_t an,const uint8_t *b,size_t bn,uint8_t **e,size_t *en){return BLEProviderCall(@"notify",a,an,b,bn,NULL,NULL,e,en);}
static int BLEDrainProvider(uint8_t **o,size_t *on,uint8_t **e,size_t *en){return BLEProviderCall(@"drain_events",NULL,0,NULL,0,o,on,e,en);}

static PyObject *BLEStart(PyObject *self, PyObject *args);
static PyObject *BLESetIdentity(PyObject *self, PyObject *args);
static PyObject *BLEStop(PyObject *self, PyObject *args);
static PyObject *BLEStartScanning(PyObject *self, PyObject *args);
static PyObject *BLEStopScanning(PyObject *self, PyObject *args);
static PyObject *BLEStartAdvertising(PyObject *self, PyObject *args);
static PyObject *BLEStopAdvertising(PyObject *self, PyObject *args);
static PyObject *BLEConnect(PyObject *self, PyObject *args);
static PyObject *BLEDisconnect(PyObject *self, PyObject *args);
static PyObject *BLEWrite(PyObject *self, PyObject *args);
static PyObject *BLENotify(PyObject *self, PyObject *args);
static PyObject *BLEDrainEvents(PyObject *self, PyObject *args);

static PyMethodDef bleMethods[] = {
    {"start", BLEStart, METH_VARARGS, NULL}, {"set_identity", BLESetIdentity, METH_VARARGS, NULL},
    {"stop", BLEStop, METH_VARARGS, NULL},
    {"start_scanning", BLEStartScanning, METH_VARARGS, NULL}, {"stop_scanning", BLEStopScanning, METH_VARARGS, NULL},
    {"start_advertising", BLEStartAdvertising, METH_VARARGS, NULL}, {"stop_advertising", BLEStopAdvertising, METH_VARARGS, NULL},
    {"connect", BLEConnect, METH_VARARGS, NULL}, {"disconnect", BLEDisconnect, METH_VARARGS, NULL},
    {"write", BLEWrite, METH_VARARGS, NULL}, {"notify", BLENotify, METH_VARARGS, NULL},
    {"drain_events", BLEDrainEvents, METH_VARARGS, NULL}, {NULL, NULL, 0, NULL}
};
static struct PyModuleDef bleModule = {PyModuleDef_HEAD_INIT, "_starling_ble_ios", NULL, -1, bleMethods};

static PyObject *CreateBLEModule(void) { return PyModule_Create(&bleModule); }

static StarlingBLECallbacks BLECallbacksSnapshot(void) {
    EnsureRuntimeLock();
    [bleRegistryLock lock];
    StarlingBLECallbacks callbacks = bleCallbacks;
    [bleRegistryLock unlock];
    return callbacks;
}

static void BLEReleaseBuffer(StarlingBLECallbacks callbacks, uint8_t *buffer) {
    if (buffer != NULL && callbacks.free_buffer != NULL) callbacks.free_buffer(buffer);
}

static PyObject *BLEFailure(StarlingBLECallbacks callbacks, int success, uint8_t *error, size_t errorLength) {
    NSString *message = nil;
    if (error != NULL && errorLength <= (size_t)PY_SSIZE_T_MAX) {
        NSString *decoded = [[NSString alloc] initWithBytes:error length:errorLength encoding:NSUTF8StringEncoding];
        if (decoded != nil) message = decoded;
    }
    BLEReleaseBuffer(callbacks, error);
    if (!success) {
        PyErr_SetString(PyExc_RuntimeError, message.UTF8String ?: "iOS BLE callback failed");
        return NULL;
    }
    return Py_None;
}

static BOOL BLEStringArgument(PyObject *value, const uint8_t **bytes, size_t *length) {
    if (!PyUnicode_Check(value)) {
        PyErr_SetString(PyExc_TypeError, "expected a string");
        return NO;
    }
    Py_ssize_t size = 0;
    const char *utf8 = PyUnicode_AsUTF8AndSize(value, &size);
    if (utf8 == NULL) return NO;
    uint8_t *copy = malloc((size_t)size);
    if (copy == NULL && size != 0) { PyErr_NoMemory(); return NO; }
    if (size != 0) memcpy(copy, utf8, (size_t)size);
    *bytes = copy;
    *length = (size_t)size;
    return YES;
}

static PyObject *BLECallString(StarlingBLECallbacks callbacks, StarlingBLEStringCallback callback, PyObject *value) {
    if (callback == NULL || callbacks.free_buffer == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL;
    }
    const uint8_t *bytes; size_t length;
    if (!BLEStringArgument(value, &bytes, &length)) return NULL;
    uint8_t *error = NULL; size_t errorLength = 0;
    int success = callback(bytes, length, &error, &errorLength);
    free((void *)bytes);
    if (!success) return BLEFailure(callbacks, success, error, errorLength);
    BLEReleaseBuffer(callbacks, error);
    Py_RETURN_NONE;
}

static PyObject *BLEStart(PyObject *self, PyObject *args) {
    PyObject *value; if (!PyArg_ParseTuple(args, "U", &value)) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); return BLECallString(callbacks, callbacks.start, value);
}
static PyObject *BLESetIdentity(PyObject *self, PyObject *args) {
    PyObject *value;
    if (!PyArg_ParseTuple(args, "O", &value)) return NULL;
    if (!PyBytes_Check(value)) { PyErr_SetString(PyExc_TypeError, "identity must be bytes"); return NULL; }
    char *source; Py_ssize_t length;
    if (PyBytes_AsStringAndSize(value, &source, &length) != 0) return NULL;
    if (length != 16) { PyErr_SetString(PyExc_ValueError, "identity must be exactly 16 bytes"); return NULL; }
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot();
    if (callbacks.set_identity == NULL || callbacks.free_buffer == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL;
    }
    uint8_t *copy = malloc((size_t)length);
    if (copy == NULL) { PyErr_NoMemory(); return NULL; }
    memcpy(copy, source, (size_t)length);
    uint8_t *error = NULL; size_t errorLength = 0;
    int success = callbacks.set_identity(copy, (size_t)length, &error, &errorLength);
    free(copy);
    if (!success) return BLEFailure(callbacks, success, error, errorLength);
    BLEReleaseBuffer(callbacks, error); Py_RETURN_NONE;
}
static PyObject *BLEConnect(PyObject *self, PyObject *args) {
    PyObject *value; if (!PyArg_ParseTuple(args, "U", &value)) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); return BLECallString(callbacks, callbacks.connect, value);
}
static PyObject *BLEDisconnect(PyObject *self, PyObject *args) {
    PyObject *value; if (!PyArg_ParseTuple(args, "U", &value)) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); return BLECallString(callbacks, callbacks.disconnect, value);
}
static PyObject *BLEStop(PyObject *self, PyObject *args) {
    if (!PyArg_ParseTuple(args, "")) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); uint8_t *error = NULL; size_t length = 0;
    if (callbacks.stop == NULL || callbacks.free_buffer == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL;
    }
    int success = callbacks.stop(&error, &length);
    if (!success) return BLEFailure(callbacks, success, error, length);
    BLEReleaseBuffer(callbacks, error); Py_RETURN_NONE;
}

static PyObject *BLECallVoid(StarlingBLEVoidCallback callback) {
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); uint8_t *error = NULL; size_t length = 0;
    if (callback == NULL || callbacks.free_buffer == NULL) { PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL; }
    int success = callback(&error, &length);
    if (!success) return BLEFailure(callbacks, success, error, length);
    BLEReleaseBuffer(callbacks, error); Py_RETURN_NONE;
}
static PyObject *BLEStartScanning(PyObject *self, PyObject *args) { if (!PyArg_ParseTuple(args, "")) return NULL; return BLECallVoid(BLECallbacksSnapshot().start_scanning); }
static PyObject *BLEStopScanning(PyObject *self, PyObject *args) { if (!PyArg_ParseTuple(args, "")) return NULL; return BLECallVoid(BLECallbacksSnapshot().stop_scanning); }
static PyObject *BLEStartAdvertising(PyObject *self, PyObject *args) { if (!PyArg_ParseTuple(args, "")) return NULL; return BLECallVoid(BLECallbacksSnapshot().start_advertising); }
static PyObject *BLEStopAdvertising(PyObject *self, PyObject *args) { if (!PyArg_ParseTuple(args, "")) return NULL; return BLECallVoid(BLECallbacksSnapshot().stop_advertising); }

static PyObject *BLECallBytes(StarlingBLECallbacks callbacks, StarlingBLEBytesCallback callback, PyObject *addressValue, PyObject *dataValue) {
    if (callback == NULL || callbacks.free_buffer == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL;
    }
    const uint8_t *address; size_t addressLength;
    if (!BLEStringArgument(addressValue, &address, &addressLength)) return NULL;
    if (!PyBytes_Check(dataValue)) { free((void *)address); PyErr_SetString(PyExc_TypeError, "expected bytes"); return NULL; }
    char *source; Py_ssize_t dataLength;
    if (PyBytes_AsStringAndSize(dataValue, &source, &dataLength) != 0) { free((void *)address); return NULL; }
    uint8_t *data = malloc((size_t)dataLength);
    if (data == NULL && dataLength != 0) { free((void *)address); PyErr_NoMemory(); return NULL; }
    if (dataLength != 0) memcpy(data, source, (size_t)dataLength);
    uint8_t *error = NULL; size_t errorLength = 0;
    int success = callback(address, addressLength, data, (size_t)dataLength, &error, &errorLength);
    free((void *)address); free(data);
    if (!success) return BLEFailure(callbacks, success, error, errorLength);
    BLEReleaseBuffer(callbacks, error); Py_RETURN_NONE;
}
static PyObject *BLEWrite(PyObject *self, PyObject *args) {
    PyObject *address, *data; if (!PyArg_ParseTuple(args, "UO", &address, &data)) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); return BLECallBytes(callbacks, callbacks.write, address, data);
}
static PyObject *BLENotify(PyObject *self, PyObject *args) {
    PyObject *address, *data; if (!PyArg_ParseTuple(args, "UO", &address, &data)) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); return BLECallBytes(callbacks, callbacks.notify, address, data);
}
static PyObject *BLEDrainEvents(PyObject *self, PyObject *args) {
    if (!PyArg_ParseTuple(args, "")) return NULL;
    StarlingBLECallbacks callbacks = BLECallbacksSnapshot(); uint8_t *json = NULL, *error = NULL;
    size_t jsonLength = 0, errorLength = 0;
    if (callbacks.drain_events == NULL || callbacks.free_buffer == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callbacks are not registered"); return NULL;
    }
    int success = callbacks.drain_events(&json, &jsonLength, &error, &errorLength);
    if (!success) { BLEReleaseBuffer(callbacks, json); return BLEFailure(callbacks, success, error, errorLength); }
    BLEReleaseBuffer(callbacks, error);
    PyObject *jsonModule = PyImport_ImportModule("json");
    PyObject *loads = jsonModule == NULL ? NULL : PyObject_GetAttrString(jsonModule, "loads");
    Py_XDECREF(jsonModule);
    PyObject *text = NULL;
    if (json == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "iOS BLE callback returned a null JSON buffer");
    } else if (jsonLength > (size_t)PY_SSIZE_T_MAX) {
        PyErr_SetString(PyExc_OverflowError, "iOS BLE JSON buffer is too large");
    } else {
        text = PyUnicode_DecodeUTF8((const char *)json, (Py_ssize_t)jsonLength, "strict");
    }
    BLEReleaseBuffer(callbacks, json);
    PyObject *result = text == NULL || loads == NULL ? NULL : PyObject_CallFunctionObjArgs(loads, text, NULL);
    Py_XDECREF(text); Py_XDECREF(loads);
    if (result == NULL && !PyErr_Occurred()) PyErr_SetString(PyExc_RuntimeError, "iOS BLE callback returned invalid JSON");
    return result;
}

static void EnsureRuntimeLock(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        runtimeLock = [[NSRecursiveLock alloc] init];
        bleRegistryLock = [[NSLock alloc] init];
    });
}

static NSString *PythonExceptionMessage(void) {
    if (!PyErr_Occurred()) return @"Unknown Python runtime error";

    PyObject *type = NULL;
    PyObject *value = NULL;
    PyObject *traceback = NULL;
    PyErr_Fetch(&type, &value, &traceback);
    PyErr_NormalizeException(&type, &value, &traceback);
    PyObject *description = value == NULL ? NULL : PyObject_Str(value);
    const char *utf8 = description == NULL ? NULL : PyUnicode_AsUTF8(description);
    NSString *message = utf8 == NULL ? @"Python runtime call failed" : [NSString stringWithUTF8String:utf8];
    Py_XDECREF(description);
    Py_XDECREF(type);
    Py_XDECREF(value);
    Py_XDECREF(traceback);
    return message;
}

static NSString *BridgeErrorLine(NSString *message) {
    NSData *data = [message dataUsingEncoding:NSUTF8StringEncoding];
    NSString *encoded = [data base64EncodedStringWithOptions:0];
    return [NSString stringWithFormat:@"ERROR\t%@", encoded];
}

static BOOL AppendSearchPath(PyWideStringList *paths, NSString *path, NSString **error) {
    wchar_t *widePath = Py_DecodeLocale(path.fileSystemRepresentation, NULL);
    if (widePath == NULL) {
        *error = @"Could not decode the bundled Python path.";
        return NO;
    }
    PyStatus status = PyWideStringList_Append(paths, widePath);
    PyMem_RawFree(widePath);
    if (PyStatus_Exception(status)) {
        *error = [NSString stringWithUTF8String:status.err_msg ?: "Could not add the Python search path."];
        return NO;
    }
    return YES;
}

NSString * _Nullable StarlingPythonInitialize(NSString *pythonHome, NSString *modulesPath, NSString *executablePath) {
    EnsureRuntimeLock();
    [runtimeLock lock];
    if (runtimeInitialized) {
        [runtimeLock unlock];
        return nil;
    }

    static dispatch_once_t moduleToken;
    static int moduleStatus = -1;
    dispatch_once(&moduleToken, ^{ moduleStatus = PyImport_AppendInittab("_starling_ble_ios", CreateBLEModule); });
    if (moduleStatus != 0) {
        [runtimeLock unlock];
        return @"Could not register the built-in iOS BLE module.";
    }

    PyPreConfig preconfig;
    PyPreConfig_InitPythonConfig(&preconfig);
    preconfig.utf8_mode = 1;
    PyStatus status = Py_PreInitialize(&preconfig);
    if (PyStatus_Exception(status)) {
        NSString *message = [NSString stringWithUTF8String:status.err_msg ?: "Could not preinitialize Python."];
        [runtimeLock unlock];
        return message;
    }

    PyConfig config;
    PyConfig_InitPythonConfig(&config);
    config.buffered_stdio = 0;
    // iOS owns lifecycle and calls STOP explicitly; the interpreter may be
    // initialized from a Kotlin coroutine worker rather than the main thread.
    config.install_signal_handlers = 0;
    config.use_environment = 0;
    config.user_site_directory = 0;
    config.write_bytecode = 0;
    config.site_import = 0;
    config.parse_argv = 0;
    status = PyConfig_SetBytesString(&config, &config.home, pythonHome.fileSystemRepresentation);
    if (PyStatus_Exception(status)) {
        NSString *message = [NSString stringWithUTF8String:status.err_msg ?: "Could not set Python's home directory."];
        PyConfig_Clear(&config);
        [runtimeLock unlock];
        return message;
    }
    status = PyConfig_SetBytesString(&config, &config.prefix, pythonHome.fileSystemRepresentation);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.exec_prefix, pythonHome.fileSystemRepresentation);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.base_prefix, pythonHome.fileSystemRepresentation);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.base_exec_prefix, pythonHome.fileSystemRepresentation);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.program_name, executablePath.fileSystemRepresentation);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.executable, executablePath.fileSystemRepresentation);
    if (PyStatus_Exception(status)) {
        NSString *message = [NSString stringWithUTF8String:status.err_msg ?: "Could not set Python's prefixes."];
        PyConfig_Clear(&config);
        [runtimeLock unlock];
        return message;
    }

    config.module_search_paths_set = 1;
    NSString *versionPath = [pythonHome stringByAppendingPathComponent:@"lib/python3.13"];
    NSString *dynamicPath = [versionPath stringByAppendingPathComponent:@"lib-dynload"];
    NSString *pathError = nil;
    if (!AppendSearchPath(&config.module_search_paths, versionPath, &pathError) ||
        !AppendSearchPath(&config.module_search_paths, dynamicPath, &pathError)) {
        PyConfig_Clear(&config);
        [runtimeLock unlock];
        return pathError;
    }

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) {
        NSString *message = [NSString stringWithUTF8String:status.err_msg ?: "Could not initialize Python."];
        [runtimeLock unlock];
        return message;
    }

    PyObject *sysPath = PySys_GetObject("path");
    PyObject *modulesPathValue = PyUnicode_DecodeFSDefault(modulesPath.fileSystemRepresentation);
    if (sysPath == NULL || modulesPathValue == NULL || PyList_Insert(sysPath, 0, modulesPathValue) != 0) {
        Py_XDECREF(modulesPathValue);
        NSString *message = PythonExceptionMessage();
        [runtimeLock unlock];
        return message;
    }
    Py_DECREF(modulesPathValue);

    PyObject *module = PyImport_ImportModule("starling_ios_runtime");
    if (module == NULL) {
        NSString *message = PythonExceptionMessage();
        [runtimeLock unlock];
        return message;
    }
    runtimeDispatcher = PyObject_GetAttrString(module, "invoke");
    Py_DECREF(module);
    if (runtimeDispatcher == NULL || !PyCallable_Check(runtimeDispatcher)) {
        NSString *message = PythonExceptionMessage();
        Py_XDECREF(runtimeDispatcher);
        runtimeDispatcher = NULL;
        [runtimeLock unlock];
        return message;
    }

    runtimeInitialized = YES;
    PyEval_SaveThread();
    [runtimeLock unlock];
    return nil;
}

void StarlingBLERegisterCallbacks(const StarlingBLECallbacks *callbacks) {
    EnsureRuntimeLock();
    [bleRegistryLock lock];
    if (callbacks == NULL) memset(&bleCallbacks, 0, sizeof(bleCallbacks));
    else bleCallbacks = *callbacks;
    [bleRegistryLock unlock];
}

void StarlingBLERegisterProvider(id<StarlingBLEProvider> provider) {
    const StarlingBLECallbacks callbacks = {
        .start = BLEStartProvider, .set_identity = BLEIdentityProvider, .stop = BLEStopProvider,
        .start_scanning = BLEStartScanningProvider, .stop_scanning = BLEStopScanningProvider,
        .start_advertising = BLEStartAdvertisingProvider, .stop_advertising = BLEStopAdvertisingProvider,
        .connect = BLEConnectProvider, .disconnect = BLEDisconnectProvider,
        .write = BLEWriteProvider, .notify = BLENotifyProvider,
        .drain_events = BLEDrainProvider, .free_buffer = BLEFreeBuffer,
    };
    EnsureRuntimeLock();
    [bleRegistryLock lock];
    bleProvider = provider;
    bleCallbacks = provider == nil ? (StarlingBLECallbacks){0} : callbacks;
    [bleRegistryLock unlock];
}

NSString *StarlingPythonInvoke(NSString *command) {
    EnsureRuntimeLock();
    [runtimeLock lock];
    if (!runtimeInitialized || runtimeDispatcher == NULL) {
        [runtimeLock unlock];
        return BridgeErrorLine(@"The embedded Python runtime is not initialized.");
    }

    PyGILState_STATE gil = PyGILState_Ensure();
    NSData *commandData = [command dataUsingEncoding:NSUTF8StringEncoding];
    PyObject *argument = NULL;
    if (commandData == nil || commandData.length > (NSUInteger)PY_SSIZE_T_MAX) {
        PyErr_SetString(PyExc_OverflowError, "Python command is too large to encode");
    } else {
        argument = PyUnicode_DecodeUTF8(commandData.bytes, (Py_ssize_t)commandData.length, "strict");
    }
    PyObject *result = argument == NULL ? NULL : PyObject_CallFunctionObjArgs(runtimeDispatcher, argument, NULL);
    Py_XDECREF(argument);
    NSString *response;
    if (result == NULL) {
        response = BridgeErrorLine(PythonExceptionMessage());
    } else {
        const char *utf8 = PyUnicode_AsUTF8(result);
        response = utf8 == NULL ? BridgeErrorLine(PythonExceptionMessage())
                                : [NSString stringWithUTF8String:utf8];
        Py_DECREF(result);
    }
    PyGILState_Release(gil);
    [runtimeLock unlock];
    return response;
}
