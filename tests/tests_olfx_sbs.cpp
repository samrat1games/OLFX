// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
#include "catch_amalgamated.hpp"
#include "util/u_distortion_mesh.h"

TEST_CASE("OLFX SBS identity mapping preserves every UV channel")
{
	const float samples[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
	for (float u : samples) {
		for (float v : samples) {
			struct xrt_uv_triplet uv;
			u_compute_distortion_identity(u, v, &uv);
			CHECK(uv.r.x == u);
			CHECK(uv.r.y == v);
			CHECK(uv.g.x == u);
			CHECK(uv.g.y == v);
			CHECK(uv.b.x == u);
			CHECK(uv.b.y == v);
		}
	}
}
