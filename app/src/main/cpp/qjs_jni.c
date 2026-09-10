/* qjs_jni.c — JNI bridge for the LuaXIDE JavaScript engine (QuickJS facade).
 * Same Array<String>{status, error, treeJson} convention as luax_jni.c so the
 * Kotlin host treats both engines uniformly. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "qjs_x.h"

#define ERRLEN 512
#define LOG_TAG "luaxjs"

static jstring jstr(JNIEnv* env, const char* s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

static jobjectArray make_result(JNIEnv* env) {
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    return (*env)->NewObjectArray(env, 3, strClass, NULL);
}

JNIEXPORT jlong JNICALL
Java_dev_luaxide_engine_JsNative_nativeNew(JNIEnv* env, jclass clazz) {
    QjsX* x = qjsx_new();
    if (!x) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "qjsx_new failed");
    return (jlong)(intptr_t)x;
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_JsNative_nativeClose(JNIEnv* env, jclass clazz, jlong handle) {
    QjsX* x = (QjsX*)(intptr_t)handle;
    if (x) qjsx_free(x);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_JsNative_nativeRun(JNIEnv* env, jclass clazz, jlong handle, jstring src) {
    QjsX* x = (QjsX*)(intptr_t)handle;
    jobjectArray out = make_result(env);
    if (!x) {
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* csrc = (*env)->GetStringUTFChars(env, src, NULL);
    char err[ERRLEN];
    int r = qjsx_run(x, csrc, err, sizeof(err));
    if (csrc) (*env)->ReleaseStringUTFChars(env, src, csrc);
    if (r) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "qjsx_run error: %s", err);

    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : qjsx_last_json(x)));
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_luaxide_engine_JsNative_nativeInvoke(JNIEnv* env, jclass clazz, jlong handle, jint handlerId, jstring payload) {
    QjsX* x = (QjsX*)(intptr_t)handle;
    jobjectArray out = make_result(env);
    if (!x) {
        (*env)->SetObjectArrayElement(env, out, 0, jstr(env, "1"));
        (*env)->SetObjectArrayElement(env, out, 1, jstr(env, "engine not initialized"));
        (*env)->SetObjectArrayElement(env, out, 2, jstr(env, ""));
        return out;
    }
    const char* arg = payload ? (*env)->GetStringUTFChars(env, payload, NULL) : NULL;
    char err[ERRLEN];
    int r = qjsx_invoke(x, (int)handlerId, arg, err, sizeof(err));
    if (arg) (*env)->ReleaseStringUTFChars(env, payload, arg);
    if (r) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "qjsx_invoke(%d) error: %s", (int)handlerId, err);

    (*env)->SetObjectArrayElement(env, out, 0, jstr(env, r ? "1" : "0"));
    (*env)->SetObjectArrayElement(env, out, 1, jstr(env, r ? err : ""));
    (*env)->SetObjectArrayElement(env, out, 2, jstr(env, r ? "" : qjsx_last_json(x)));
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_luaxide_engine_JsNative_nativeTakeOutput(JNIEnv* env, jclass clazz, jlong handle) {
    QjsX* x = (QjsX*)(intptr_t)handle;
    return jstr(env, x ? qjsx_last_output(x) : "");
}

JNIEXPORT void JNICALL
Java_dev_luaxide_engine_JsNative_nativeClearOutput(JNIEnv* env, jclass clazz, jlong handle) {
    QjsX* x = (QjsX*)(intptr_t)handle;
    if (x) qjsx_clear_output(x);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "libluaxjs loaded");
    return JNI_VERSION_1_6;
}
