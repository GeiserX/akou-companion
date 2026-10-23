// SPDX-License-Identifier: GPL-3.0-or-later
// JNI over libopus for LibopusEncoder.kt. The settings come from the iOS shim
// (Sources/COpusShim/akou_opus_shim.c), compiled into this library unchanged.
#include <jni.h>
#include <stdint.h>

#include "akou_opus_shim.h"
#include "opus.h"

#define AKOU_SAMPLE_RATE 16000
#define AKOU_FRAME_SAMPLES 320
#define AKOU_MAX_PACKET 1500

static OpusEncoder *encoder_of(jlong handle) { return (OpusEncoder *)(intptr_t)handle; }

// 16 kHz mono VOIP. Returns the handle, or 0 with the libopus error in err[0].
JNIEXPORT jlong JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_create(JNIEnv *env, jobject self, jintArray err) {
    (void)self;
    int code = OPUS_OK;
    OpusEncoder *e = opus_encoder_create(AKOU_SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &code);
    if (e != NULL && code != OPUS_OK) {
        opus_encoder_destroy(e);
        e = NULL;
    }
    if (e == NULL && code == OPUS_OK) code = OPUS_ALLOC_FAIL;
    jint out = code;
    (*env)->SetIntArrayRegion(env, err, 0, 1, &out);
    return (jlong)(intptr_t)e;
}

JNIEXPORT jint JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_configure(JNIEnv *env, jobject self, jlong handle, jint bitrate) {
    (void)env;
    (void)self;
    return akou_opus_configure(encoder_of(handle), bitrate);
}

JNIEXPORT jint JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_lookahead(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    return akou_opus_lookahead(encoder_of(handle));
}

JNIEXPORT jint JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_bitrate(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    return akou_opus_bitrate(encoder_of(handle));
}

// One 20 ms frame of exactly 320 samples into `out` (exactly 1500 bytes). Returns the packet
// length, or a negative libopus error (OPUS_BAD_ARG for a wrong frame or buffer size,
// OPUS_INVALID_STATE for a destroyed encoder).
JNIEXPORT jint JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_encodeFloat(JNIEnv *env, jobject self, jlong handle, jfloatArray pcm, jbyteArray out) {
    (void)self;
    if (handle == 0) return OPUS_INVALID_STATE;
    if ((*env)->GetArrayLength(env, pcm) != AKOU_FRAME_SAMPLES) return OPUS_BAD_ARG;
    if ((*env)->GetArrayLength(env, out) != AKOU_MAX_PACKET) return OPUS_BAD_ARG;
    float frame[AKOU_FRAME_SAMPLES];
    unsigned char packet[AKOU_MAX_PACKET];
    (*env)->GetFloatArrayRegion(env, pcm, 0, AKOU_FRAME_SAMPLES, frame);
    opus_int32 n = opus_encode_float(encoder_of(handle), frame, AKOU_FRAME_SAMPLES, packet, AKOU_MAX_PACKET);
    if (n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)packet);
    return n;
}

JNIEXPORT void JNICALL
Java_io_github_geiserx_akou_opus_OpusNative_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    if (handle != 0) opus_encoder_destroy(encoder_of(handle));
}
