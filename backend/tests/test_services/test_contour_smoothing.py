# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The numpy Gaussian blur is scipy's gaussian_filter, to float precision.

It replaced scipy in the image (140 MB for this one call), so the contours a
release draws depend on it matching: a different kernel radius or edge rule
would move every contour line near a tile edge, and the neighbour-stitched
seams between tiles would stop meeting. scipy stays installed for the tests
alone, as the oracle.
"""

import numpy as np
import pytest
from scipy.ndimage import gaussian_filter

from app.services.contour_generator.contours import gaussian_blur


@pytest.mark.parametrize("shape", [(514, 514), (37, 53), (3, 3), (1, 9)])
@pytest.mark.parametrize("dtype", [np.float32, np.float64])
@pytest.mark.parametrize("sigma", [0.5, 1.0, 2.0])
def test_the_blur_matches_scipy(shape, dtype, sigma):
    """Including arrays smaller than the kernel, where the edge rule is everything."""
    rng = np.random.default_rng(7)
    elevation = (rng.random(shape) * 4000 - 200).astype(dtype)

    ours = gaussian_blur(elevation, sigma)
    theirs = gaussian_filter(elevation, sigma=sigma)

    assert ours.dtype == theirs.dtype
    np.testing.assert_allclose(ours, theirs, rtol=0, atol=4000 * 1e-6)


def test_a_flat_surface_stays_flat():
    """Normalised kernel and mirrored edges: no darkening or brightening at the border."""
    flat = np.full((20, 20), 1234.5, dtype=np.float32)
    np.testing.assert_allclose(gaussian_blur(flat, 1.0), flat, rtol=1e-6)
