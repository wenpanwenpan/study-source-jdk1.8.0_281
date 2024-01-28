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

import com.sun.javafx.sg.prism.NGCamera;
import com.sun.javafx.sg.prism.NGDefaultCamera;

// WCCamera is based on NGDefaultCamera, but the near and far clip
// values are modified according to WebKit's requirement.
// Refer TextureMapperGL.cpp, createProjectionMatrix function.

// According to w3c spec,
//
// perspective(<number>)
//
// specifies a perspective projection matrix. This matrix maps a viewing cube
// onto a pyramid whose base is infinitely far away from the viewer and whose
// peak represents the viewer's position. The viewable area is the region
// bounded by the four edges of the viewport (the portion of the browser window
// used for rendering the webpage between the viewer's position and a point
// at a distance of infinity from the viewer). The depth, given as the
// parameter to the function, represents the distance of the z=0 plane
// from the viewer. Lower values give a more flattened pyramid and therefore
// a more pronounced perspective effect. The value is given in pixels,
// so a value of 1000 gives a moderate amount of foreshortening and a value of
// 200 gives an extreme amount. The matrix is computed by starting with an
// identity matrix and replacing the value at row 3, column 4 with the value
// -1/depth. The value for depth must be greater than zero, otherwise the
// function is invalid.

public class WCCamera extends NGDefaultCamera {

    public static final NGCamera INSTANCE = new WCCamera();

    public void validate(final int w, final int h) {
        if ((w != viewWidth) || (h != viewHeight)) {
            setViewWidth(w);
            setViewHeight(h);

            projViewTx.ortho(0.0, w, h, 0.0, -9999999, 99999);
        }
    }
}

