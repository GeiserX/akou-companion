// SPDX-License-Identifier: GPL-3.0-or-later
#include "akou_opus_shim.h"
#include "opus.h"

int akou_opus_configure(void *encoder, int32_t bitrate) {
    OpusEncoder *e = (OpusEncoder *)encoder;
    int err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate))) != OPUS_OK) return err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_VBR(1))) != OPUS_OK) return err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND))) != OPUS_OK) return err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE))) != OPUS_OK) return err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_DTX(0))) != OPUS_OK) return err;
    if ((err = opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(0))) != OPUS_OK) return err;
    return OPUS_OK;
}

int32_t akou_opus_lookahead(void *encoder) {
    opus_int32 v = 0;
    int err = opus_encoder_ctl((OpusEncoder *)encoder, OPUS_GET_LOOKAHEAD(&v));
    return err == OPUS_OK ? v : err;
}

int32_t akou_opus_bitrate(void *encoder) {
    opus_int32 v = 0;
    int err = opus_encoder_ctl((OpusEncoder *)encoder, OPUS_GET_BITRATE(&v));
    return err == OPUS_OK ? v : err;
}
