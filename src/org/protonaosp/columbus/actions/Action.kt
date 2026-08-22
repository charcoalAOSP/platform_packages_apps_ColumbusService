/*
 * SPDX-FileCopyrightText: The Proton AOSP Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.protonaosp.columbus.actions

import android.content.Context

abstract class Action(val context: Context) {
    open fun canRun() = true

    abstract fun run()

    open fun destroy() {}
}
