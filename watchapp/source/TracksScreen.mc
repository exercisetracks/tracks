// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

using Toybox.Graphics;
using Toybox.WatchUi;

//! The one status layout every plain screen in this app uses: a headline and
//! a line of detail, each wrapped and shrunk to fit.
//!
//! A round 260-pixel screen clips anything drawn as a single line past about
//! twenty characters, and drawText does exactly that without complaint.
//! TextArea wraps, and picks the largest of the given fonts that still fits
//! the box — so a message never hangs off the sides, it gets smaller.
//!
//! The boxes are kept inside the circle: at 25 % and 75 % of the height the
//! visible chord is about 87 % of the width, so 76 % leaves a margin.
module TracksScreen {

    function draw(dc, headline, detail) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var boxW = (w * 76) / 100;
        var x = (w - boxW) / 2;

        var top = new WatchUi.TextArea({
            :text => headline,
            :color => Graphics.COLOR_WHITE,
            :font => [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER,
            :locX => x,
            :locY => (h * 22) / 100,
            :width => boxW,
            :height => (h * 28) / 100
        });
        top.draw(dc);

        if (detail != null) {
            var bottom = new WatchUi.TextArea({
                :text => detail,
                :color => Graphics.COLOR_LT_GRAY,
                :font => [Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY],
                :justification => Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER,
                :locX => x,
                :locY => (h * 50) / 100,
                :width => boxW,
                :height => (h * 28) / 100
            });
            bottom.draw(dc);
        }
    }
}
