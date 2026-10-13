#import "PythonRuntimeShim.h"
#import <Python/Python.h>
#import <dispatch/dispatch.h>
#include <string.h>

static NSRecursiveLock *runtimeLock;
static PyObject *runtimeDispatcher;
static BOOL runtimeInitialized;

static void EnsureRuntimeLock(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        runtimeLock = [[NSRecursiveLock alloc] init];
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

NSString *StarlingPythonInvoke(NSString *command) {
    EnsureRuntimeLock();
    [runtimeLock lock];
    if (!runtimeInitialized || runtimeDispatcher == NULL) {
        [runtimeLock unlock];
        return BridgeErrorLine(@"The embedded Python runtime is not initialized.");
    }

    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *argument = PyUnicode_DecodeUTF8(command.UTF8String, (Py_ssize_t)strlen(command.UTF8String), "strict");
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
