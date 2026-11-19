// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @brief Bounds-safe YUV_420_888 -> RGBA conversion for camera hand tracking.
 *
 * Android camera planes are not always tightly packed: row strides can exceed the visible width,
 * U/V can be semi-planar (pixel stride 2), and the last chroma byte of a shared NV12/NV21 buffer is
 * commonly omitted from one plane's reported capacity. Validate the last *accessed* byte rather
 * than requiring a full rectangular stride buffer, otherwise valid camera frames are rejected.
 *
 * @ingroup drv_android
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

static inline bool
olfx_yuv420_to_rgba(const uint8_t *y,
                    const uint8_t *u,
                    const uint8_t *v,
                    size_t y_size,
                    size_t u_size,
                    size_t v_size,
                    int y_row,
                    int u_row,
                    int u_pix,
                    int v_row,
                    int v_pix,
                    int w,
                    int h,
                    uint8_t *out,
                    size_t out_size)
{
	if (y == NULL || u == NULL || v == NULL || out == NULL || w <= 0 || h <= 0 || w > 4096 || h > 4096 ||
	    y_row < w || u_pix < 1 || u_pix > 2 || v_pix < 1 || v_pix > 2) {
		return false;
	}

	const size_t sw = (size_t)w;
	const size_t sh = (size_t)h;
	const size_t cw = (sw + 1) / 2;
	const size_t ch = (sh + 1) / 2;
	const size_t yr = (size_t)y_row;
	const size_t ur = (size_t)u_row;
	const size_t vr = (size_t)v_row;
	const size_t up = (size_t)u_pix;
	const size_t vp = (size_t)v_pix;

	if (ur < (cw - 1) * up + 1 || vr < (cw - 1) * vp + 1) {
		return false;
	}
	const size_t y_last = (sh - 1) * yr + (sw - 1);
	const size_t u_last = (ch - 1) * ur + (cw - 1) * up;
	const size_t v_last = (ch - 1) * vr + (cw - 1) * vp;
	const size_t out_need = sw * sh * 4;
	if (y_size <= y_last || u_size <= u_last || v_size <= v_last || out_size < out_need) {
		return false;
	}

	for (size_t row = 0; row < sh; row++) {
		const uint8_t *yp = y + row * yr;
		const uint8_t *up_row = u + (row >> 1) * ur;
		const uint8_t *vp_row = v + (row >> 1) * vr;
		uint8_t *op = out + row * sw * 4;
		for (size_t x = 0; x < sw; x += 2) {
			const int U = (int)up_row[(x >> 1) * up] - 128;
			const int V = (int)vp_row[(x >> 1) * vp] - 128;
			const int r_add = (91881 * V) >> 16;
			const int g_add = (22554 * U + 46802 * V) >> 16;
			const int b_add = (116130 * U) >> 16;
			for (size_t k = 0; k < 2 && x + k < sw; k++) {
				const int Y = yp[x + k];
				int r = Y + r_add;
				int g = Y - g_add;
				int b = Y + b_add;
				op[0] = (uint8_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
				op[1] = (uint8_t)(g < 0 ? 0 : (g > 255 ? 255 : g));
				op[2] = (uint8_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
				op[3] = 255;
				op += 4;
			}
		}
	}
	return true;
}

#ifdef __cplusplus
}
#endif
