/*
 * openjavacard-ndef: JavaCard applet implementing an NDEF type 4 tag
 * Copyright (C) 2015-2024 Ingo Albrecht <copyright@promovicz.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */
package org.openjavacard.ndef.tmc;

import javacard.framework.Shareable;

/**
 * Shareable interface for applets that provide data to the TMC dynamic URL.
 *
 * The TMC applet calls this interface while building the cached dynamic NDEF
 * response. Implementations must write at most maxLen bytes into out and return
 * the number of bytes written. The TMC applet hex-encodes those bytes into the
 * configured URL field.
 */
public interface TmcDataSource extends Shareable {
    short getTmcData(byte[] out, short outOff, short maxLen);
}
