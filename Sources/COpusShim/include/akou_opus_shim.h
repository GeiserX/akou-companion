// SPDX-License-Identifier: GPL-3.0-or-later
// Non-variadic wrappers over opus_encoder_ctl, which Swift cannot call directly.
#ifndef AKOU_OPUS_SHIM_H
#define AKOU_OPUS_SHIM_H

#include <stdint.h>

/// Applies akou-companion's encoder settings: the given bitrate, VBR, wideband at most, voice signal,
/// no DTX and no in-band FEC (the live path runs over TCP, which loses nothing).
/// `encoder` is an `OpusEncoder *`. Returns OPUS_OK (0) or the first libopus error.
int akou_opus_configure(void *encoder, int32_t bitrate);

/// The encoder's lookahead in samples at its own sample rate, or a negative libopus error.
int32_t akou_opus_lookahead(void *encoder);

/// The encoder's current bitrate in bits per second, or a negative libopus error.
int32_t akou_opus_bitrate(void *encoder);

#endif
