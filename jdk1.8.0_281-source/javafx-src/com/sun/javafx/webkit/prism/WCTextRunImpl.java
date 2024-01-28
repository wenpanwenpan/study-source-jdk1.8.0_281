/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.webkit.graphics;

import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.text.TextRun;

public final class WCTextRunImpl implements WCTextRun {
    private final TextRun run;

    public WCTextRunImpl(GlyphList run) {
        this.run = (TextRun) run;
    }

    @Override
    public int getGlyphCount() {
        return run.getGlyphCount();
    }

    @Override
    public boolean isLeftToRight() {
        return run.isLeftToRight();
    }

    @Override
    public int getGlyph(int index) {
        return index < run.getGlyphCount() ? run.getGlyphCode(index) : 0;
    }

    // Avoid repeated allocation
    private static float POS_AND_ADVANCE[] = new float[4];

    @Override
    public float[] getGlyphPosAndAdvance(int glyphIndex) {
        POS_AND_ADVANCE[0] = run.getPosX(glyphIndex);
        POS_AND_ADVANCE[1] = run.getPosY(glyphIndex);
        POS_AND_ADVANCE[2] = run.getAdvance(glyphIndex);
        // FIXME: We don't yet support Y advance from prism.
        POS_AND_ADVANCE[3] = 0;
        return POS_AND_ADVANCE;
    }

    @Override
    public int getStart() {
        return run.getStart();
    }

    @Override
    public int getEnd() {
        return run.getEnd();
    }

    @Override
    public int getCharOffset(int index) {
        return run.getCharOffset(index);
    }
}
