#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <android/log.h>
#include "lx.h"

#define ERRLEN 512
#define LOG_TAG "luax"

static JavaVM* g_vm = NULL;
static jobject g_logger = NULL;
static jmethodID g_logMethod = NULL;

static jstring jstr(JNIEnv* env, const char* s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

static JNIEnv* get_env(int* attached) {
    *attached = 0;
    if (!g_vm) return NULL;
    JNIEnv* env = NULL;
    jint st = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
        *attached = 1;
    } else if (st != JNI_OK) {
        return NULL;
    }
    return env;
}

static void emit_java(int level, const char* tag, const char* msg) {
    if (!g_logger || !g_logMethod || !msg) return;
    int attached = 0;
    JNIEnv* env = get_env(&attached);
    if (!env) return;
    jstring jtag = jstr(env, tag ? tag : LOG_TAG);
    jstring jmsg = jstr(env, msg);
    (*env)->CallVoidMethod(env, g_logger, g_logMethod, (jint)level, jtag, jmsg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, jtag);
    (*env)->DeleteLocalRef(env, jmsg);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

static void nlog(int prio, int jlevel, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(prio, LOG_TAG, "%s", buf);
    emit_java(jlevel, LOG_TAG, buf);
}

/* jlevel: 0=V 1=D 2=I 3=W 4=E */
JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeSetLogger(JNIEnv* env, jclass clazz, jobject logger) {
    if (g_logger) {
        (*env)->DeleteGlobalRef(env, g_logger);
        g_logger = NULL;
        g_logMethod = NULL;
    }
    if (!logger) return;
    g_logger = (*env)->NewGlobalRef(env, logger);
    jclass cls = (*env)->GetObjectClass(env, logger);
    g_logMethod = (*env)->GetMethodID(env, cls, "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_logMethod) {
        (*env)->ExceptionClear(env);
        if (g_logger) { (*env)->DeleteGlobalRef(env, g_logger); g_logger = NULL; }
        return;
    }
    nlog(ANDROID_LOG_INFO, 2, "native logger attached");
}

JNIEXPORT jlong JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeNew(JNIEnv* env, jclass clazz) {
    lx_State* S = lx_new();
    if (!S) {
        nlog(ANDROID_LOG_ERROR, 4, "lx_new failed");
        return 0;
    }
    nlog(ANDROID_LOG_DEBUG, 1, "engine state created %p", (void*)S);
    return (jlong)(intptr_t)S;
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeClose(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) {
        nlog(ANDROID_LOG_DEBUG, 1, "engine state closed %p", (void*)S);
        lx_close(S);
    }
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeSetStepLimit(JNIEnv* env, jclass clazz, jlong handle, jlong steps) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_set_step_limit(S, (long)steps);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeRun(JNIEnv* env, jclass clazz, jlong handle, jstring src) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 3, strClass, NULL);
    if (!S) {
        nlog(ANDROID_LOG_ERROR, 4, "nativeRun: engine not initialized");
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* csrc = (*env)->GetStringUTFChars(env, src, NULL);
    char err[ERRLEN];
    int r = lx_run(S, csrc, err, sizeof(err));
    (*env)->ReleaseStringUTFChars(env, src, csrc);

    if (r) {
        nlog(ANDROID_LOG_ERROR, 4, "lx_run error: %s", err);
    } else {
        nlog(ANDROID_LOG_DEBUG, 1, "lx_run ok");
    }

    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : lx_last_json(S)));
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeInvoke(JNIEnv* env, jclass clazz, jlong handle, jint handlerId) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 3, strClass, NULL);
    if (!S) {
        nlog(ANDROID_LOG_ERROR, 4, "nativeInvoke: engine not initialized");
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    char err[ERRLEN];
    int r = lx_invoke(S, (int)handlerId, err, sizeof(err));
    if (r) {
        nlog(ANDROID_LOG_ERROR, 4, "lx_invoke(%d) error: %s", (int)handlerId, err);
    } else {
        nlog(ANDROID_LOG_DEBUG, 1, "lx_invoke(%d) ok", (int)handlerId);
    }
    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : lx_last_json(S)));
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeTakeOutput(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return jstr(env, "");
    return jstr(env, lx_last_output(S));
}


JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeClearOutput(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_clear_output(S);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeRepl(JNIEnv* env, jclass clazz, jlong handle, jstring src) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, 3, strClass, NULL);
    if (!S) {
        nlog(ANDROID_LOG_ERROR, 4, "nativeRepl: engine not initialized");
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* csrc = (*env)->GetStringUTFChars(env, src, NULL);
    char err[ERRLEN];
    int r = lx_repl(S, csrc, err, sizeof(err));
    (*env)->ReleaseStringUTFChars(env, src, csrc);
    if (r) {
        nlog(ANDROID_LOG_ERROR, 4, "lx_repl error: %s", err);
    } else {
        nlog(ANDROID_LOG_DEBUG, 1, "lx_repl ok");
    }
    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
    return out;
}


JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeCancel(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_cancel(S);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeClearCancel(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_clear_cancel(S);
}

JNIEXPORT jboolean JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeIsCancelled(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    return (jboolean)(S && lx_is_cancelled(S) ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativePushStdin(JNIEnv* env, jclass clazz, jlong handle, jstring line) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    const char* c = line ? (*env)->GetStringUTFChars(env, line, NULL) : "";
    lx_push_stdin(S, c ? c : "");
    if (line && c) (*env)->ReleaseStringUTFChars(env, line, c);
}

JNIEXPORT jboolean JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeWaitingStdin(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    return (jboolean)(S && lx_waiting_stdin(S) ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeSetRootfs(JNIEnv* env, jclass clazz, jlong handle, jstring path) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    if (!path) { lx_set_rootfs(S, ""); return; }
    const char* c = (*env)->GetStringUTFChars(env, path, NULL);
    lx_set_rootfs(S, c ? c : "");
    if (c) (*env)->ReleaseStringUTFChars(env, path, c);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeSetModroot(JNIEnv* env, jclass clazz, jlong handle, jstring path) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    if (!path) { lx_set_modroot(S, ""); return; }
    const char* c = (*env)->GetStringUTFChars(env, path, NULL);
    lx_set_modroot(S, c ? c : "");
    if (c) (*env)->ReleaseStringUTFChars(env, path, c);
}


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "libluax loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK && g_logger) {
        (*env)->DeleteGlobalRef(env, g_logger);
        g_logger = NULL;
        g_logMethod = NULL;
    }
    g_vm = NULL;
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugEnable(JNIEnv* env, jclass clazz, jlong handle, jboolean enabled) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_enable(S, enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugSetBreakpoints(JNIEnv* env, jclass clazz, jlong handle, jintArray lines) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    if (!lines) { lx_debug_clear_breakpoints(S); return; }
    jsize n = (*env)->GetArrayLength(env, lines);
    jint* arr = (*env)->GetIntArrayElements(env, lines, NULL);
    if (!arr) return;
    int tmp[256];
    int m = n > 256 ? 256 : (int)n;
    for (int i = 0; i < m; i++) tmp[i] = (int)arr[i];
    (*env)->ReleaseIntArrayElements(env, lines, arr, JNI_ABORT);
    lx_debug_set_breakpoints(S, tmp, m);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugContinue(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_continue(S);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugStep(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_step(S);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugStop(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_stop(S);
}

JNIEXPORT jboolean JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugIsPaused(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    return (jboolean)(S && lx_debug_is_paused(S) ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jint JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugPauseLine(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    return (jint)(S ? lx_debug_pause_line(S) : 0);
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugLocals(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return jstr(env, "[]");
    return jstr(env, lx_debug_locals(S));
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugStack(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return jstr(env, "[]");
    return jstr(env, lx_debug_stack(S));
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugStepOut(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_step_out(S);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugSetBreakpointsEx(JNIEnv* env, jclass clazz, jlong handle, jintArray lines, jobjectArray conds) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    if (!lines) { lx_debug_clear_breakpoints(S); return; }
    jsize n = (*env)->GetArrayLength(env, lines);
    jint* arr = (*env)->GetIntArrayElements(env, lines, NULL);
    if (!arr) return;
    int tmp[256];
    const char* ctmp[256];
    char storage[256][96];
    int m = n > 256 ? 256 : (int)n;
    for (int i = 0; i < m; i++) {
        tmp[i] = (int)arr[i];
        ctmp[i] = NULL;
        storage[i][0] = 0;
        if (conds) {
            jsize cn = (*env)->GetArrayLength(env, conds);
            if (i < cn) {
                jstring js = (jstring)(*env)->GetObjectArrayElement(env, conds, i);
                if (js) {
                    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
                    if (utf) {
                        snprintf(storage[i], sizeof(storage[i]), "%s", utf);
                        (*env)->ReleaseStringUTFChars(env, js, utf);
                        ctmp[i] = storage[i];
                    }
                    (*env)->DeleteLocalRef(env, js);
                }
            }
        }
    }
    (*env)->ReleaseIntArrayElements(env, lines, arr, JNI_ABORT);
    lx_debug_set_breakpoints_ex(S, tmp, ctmp, m);
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugEval(JNIEnv* env, jclass clazz, jlong handle, jstring expr) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S || !expr) return jstr(env, "{\"ok\":false,\"error\":\"bad args\"}");
    const char* utf = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!utf) return jstr(env, "{\"ok\":false,\"error\":\"utf\"}");
    const char* res = lx_debug_eval(S, utf);
    (*env)->ReleaseStringUTFChars(env, expr, utf);
    return jstr(env, res);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugSetBreakOnError(JNIEnv* env, jclass clazz, jlong handle, jboolean enabled) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (S) lx_debug_set_break_on_error(S, enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugPauseReason(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    return (jint)(S ? lx_debug_pause_reason(S) : 0);
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugLastError(JNIEnv* env, jclass clazz, jlong handle) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return jstr(env, "");
    return jstr(env, lx_debug_last_error(S));
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_LuaxNative_nativeDebugSetBreakpointsFull(
    JNIEnv* env, jclass clazz, jlong handle,
    jintArray lines, jobjectArray conds, jobjectArray logs, jbooleanArray logOnly
) {
    lx_State* S = (lx_State*)(intptr_t)handle;
    if (!S) return;
    if (!lines) { lx_debug_clear_breakpoints(S); return; }
    jsize n = (*env)->GetArrayLength(env, lines);
    jint* arr = (*env)->GetIntArrayElements(env, lines, NULL);
    if (!arr) return;
    int tmp[256];
    const char* ctmp[256];
    const char* ltmp[256];
    int otmp[256];
    char cstor[256][96];
    char lstor[256][128];
    int m = n > 256 ? 256 : (int)n;
    jboolean* oarr = NULL;
    if (logOnly) oarr = (*env)->GetBooleanArrayElements(env, logOnly, NULL);
    for (int i = 0; i < m; i++) {
        tmp[i] = (int)arr[i];
        ctmp[i] = NULL; ltmp[i] = NULL; otmp[i] = 0;
        cstor[i][0] = 0; lstor[i][0] = 0;
        if (oarr) otmp[i] = oarr[i] ? 1 : 0;
        if (conds) {
            jsize cn = (*env)->GetArrayLength(env, conds);
            if (i < cn) {
                jstring js = (jstring)(*env)->GetObjectArrayElement(env, conds, i);
                if (js) {
                    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
                    if (utf) {
                        snprintf(cstor[i], sizeof(cstor[i]), "%s", utf);
                        (*env)->ReleaseStringUTFChars(env, js, utf);
                        ctmp[i] = cstor[i];
                    }
                    (*env)->DeleteLocalRef(env, js);
                }
            }
        }
        if (logs) {
            jsize ln = (*env)->GetArrayLength(env, logs);
            if (i < ln) {
                jstring js = (jstring)(*env)->GetObjectArrayElement(env, logs, i);
                if (js) {
                    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
                    if (utf) {
                        snprintf(lstor[i], sizeof(lstor[i]), "%s", utf);
                        (*env)->ReleaseStringUTFChars(env, js, utf);
                        ltmp[i] = lstor[i];
                    }
                    (*env)->DeleteLocalRef(env, js);
                }
            }
        }
    }
    if (oarr) (*env)->ReleaseBooleanArrayElements(env, logOnly, oarr, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, lines, arr, JNI_ABORT);
    lx_debug_set_breakpoints_full(S, tmp, ctmp, ltmp, otmp, m);
}
