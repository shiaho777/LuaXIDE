/* mpy_jni.c — JNI bridge for the LuaXIDE Python engine (MicroPython facade).
 * Same Array<String>{status, error, treeJson} convention as luax_jni.c /
 * qjs_jni.c so the Kotlin host treats all engines uniformly.
 *
 * Note: the MicroPython facade keeps its runtime state process-global
 * (g_active), so exactly ONE engine handle may exist per process; the
 * Kotlin host enforces single-instance usage on its dedicated thread.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "mpy_x.h"

#define ERRLEN 512
#define LOG_TAG "luaxpy"

static jstring jstr(JNIEnv* env, const char* s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

static jobjectArray make_result(JNIEnv* env) {
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    return (*env)->NewObjectArray(env, 3, strClass, NULL);
}

JNIEXPORT jlong JNICALL
Java_dev_luaxide_engine_PyNative_nativeNew(JNIEnv* env, jclass clazz) {
    MpyX* x = mpyx_new();
    if (!x) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpyx_new failed");
    return (jlong)(intptr_t)x;
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_PyNative_nativeClose(JNIEnv* env, jclass clazz, jlong handle) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    if (x) mpyx_free(x);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_PyNative_nativeRun(JNIEnv* env, jclass clazz, jlong handle, jstring src) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    jobjectArray out = make_result(env);
    if (!x) {
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* csrc = (*env)->GetStringUTFChars(env, src, NULL);
    char err[ERRLEN];
    int r = mpyx_run(x, csrc, err, sizeof(err));
    if (csrc) (*env)->ReleaseStringUTFChars(env, src, csrc);
    if (r) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpyx_run error: %s", err);

    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : mpyx_last_json(x)));
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_PyNative_nativeInvoke(JNIEnv* env, jclass clazz, jlong handle, jint handlerId, jstring payload) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    jobjectArray out = make_result(env);
    if (!x) {
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* arg = payload ? (*env)->GetStringUTFChars(env, payload, NULL) : NULL;
    char err[ERRLEN];
    int r = mpyx_invoke(x, (int)handlerId, arg, err, sizeof(err));
    if (arg) (*env)->ReleaseStringUTFChars(env, payload, arg);
    if (r) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpyx_invoke(%d) error: %s", (int)handlerId, err);

    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : mpyx_last_json(x)));
    return out;
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_PyNative_nativeCancel(JNIEnv* env, jclass clazz, jlong handle) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    if (x) mpyx_cancel(x);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_PyNative_nativeClearCancel(JNIEnv* env, jclass clazz, jlong handle) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    if (x) mpyx_clear_cancel(x);
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_PyNative_nativeSetStepLimit(JNIEnv* env, jclass clazz, jlong handle, jlong steps) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    if (x) mpyx_set_step_limit(x, (long)steps);
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_PyNative_nativeTakeOutput(JNIEnv* env, jclass clazz, jlong handle) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    return jstr(env, x ? mpyx_last_output(x) : "");
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_PyNative_nativeClearOutput(JNIEnv* env, jclass clazz, jlong handle) {
    MpyX* x = (MpyX*)(intptr_t)handle;
    if (x) mpyx_clear_output(x);
}
