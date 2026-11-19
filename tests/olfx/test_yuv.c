// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/* Host-testable regression for the Android camera YUV path used by hand tracking. */
#include "olfx_yuv.h"

#include <assert.h>
#include <stdint.h>
#include <string.h>

static void
fill_gray(uint8_t *y, size_t y_size, uint8_t *u, size_t u_size, uint8_t *v, size_t v_size)
{
	memset(y, 16, y_size);
	memset(u, 128, u_size);
	memset(v, 128, v_size);
}

int
main(void)
{
	uint8_t out[4 * 4 * 4];

	/* Tight planar 4:2:0. */
	{
		uint8_t y[16], u[4], v[4];
		fill_gray(y, sizeof(y), u, sizeof(u), v, sizeof(v));
		memset(out, 0xAA, sizeof(out));
		assert(olfx_yuv420_to_rgba(y, u, v, sizeof(y), sizeof(u), sizeof(v), 4, 2, 1, 2, 1, 4, 4, out,
		                           sizeof(out)));
		for (size_t i = 0; i < 16; i++) {
			assert(out[i * 4 + 0] == 16);
			assert(out[i * 4 + 1] == 16);
			assert(out[i * 4 + 2] == 16);
			assert(out[i * 4 + 3] == 255);
		}
	}

	/* NV21-style semi-planar planes with padded row stride and the common truncated final byte. */
	{
		uint8_t y[4 * 4];
		uint8_t u[7], v[7]; /* rowStride=4, chroma rows=2, pixelStride=2 -> last accessed byte is 6. */
		memset(y, 235, sizeof(y));
		memset(u, 0, sizeof(u));
		memset(v, 0, sizeof(v));
		for (size_t i = 0; i < sizeof(u); i += 2) {
			u[i] = 128;
			v[i] = 128;
		}
		memset(out, 0xAA, sizeof(out));
		assert(olfx_yuv420_to_rgba(y, u, v, sizeof(y), sizeof(u), sizeof(v), 4, 4, 2, 4, 2, 4, 4, out,
		                           sizeof(out)));
		for (size_t i = 0; i < 16; i++) {
			assert(out[i * 4 + 0] == 235);
			assert(out[i * 4 + 1] == 235);
			assert(out[i * 4 + 2] == 235);
			assert(out[i * 4 + 3] == 255);
		}
	}

	/* Odd dimensions and independent U/V strides. */
	{
		uint8_t y[13], u[7], v[9];
		fill_gray(y, sizeof(y), u, sizeof(u), v, sizeof(v));
		memset(out, 0xAA, sizeof(out));
		assert(olfx_yuv420_to_rgba(y, u, v, sizeof(y), sizeof(u), sizeof(v), 5, 4, 2, 5, 2, 3, 3, out,
		                           sizeof(out)));
		for (size_t i = 0; i < 9; i++) {
			assert(out[i * 4 + 0] == 16);
			assert(out[i * 4 + 3] == 255);
		}
	}

	/* Reject invalid/bounds-unsafe input instead of producing a black frame or reading past a plane. */
	{
		uint8_t y[16], u[4], v[4];
		fill_gray(y, sizeof(y), u, sizeof(u), v, sizeof(v));
		memset(out, 0xAA, sizeof(out));
		assert(!olfx_yuv420_to_rgba(y, u, v, 15, sizeof(u), sizeof(v), 4, 2, 1, 2, 1, 4, 4, out, sizeof(out)));
		assert(!olfx_yuv420_to_rgba(y, u, v, sizeof(y), 3, sizeof(v), 4, 2, 1, 2, 1, 4, 4, out, sizeof(out)));
		assert(!olfx_yuv420_to_rgba(y, u, v, sizeof(y), sizeof(u), sizeof(v), 4, 2, 3, 2, 1, 4, 4, out, sizeof(out)));
		assert(!olfx_yuv420_to_rgba(y, u, v, sizeof(y), sizeof(u), sizeof(v), 4, 2, 1, 2, 1, 4, 4, out, 63));
		assert(!olfx_yuv420_to_rgba(NULL, u, v, sizeof(y), sizeof(u), sizeof(v), 4, 2, 1, 2, 1, 4, 4, out, sizeof(out)));
		for (size_t i = 0; i < sizeof(out); i++) {
			assert(out[i] == 0xAA);
		}
	}
	return 0;
}
