/*
 * SPDX-FileCopyrightText: The Proton AOSP Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0
 */

package org.protonaosp.columbus.settings

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.*
import android.provider.SearchIndexablesProvider
import org.protonaosp.columbus.R

class SearchProvider : SearchIndexablesProvider() {
    override fun onCreate() = true

    override fun queryXmlResources(projection: Array<String>?): Cursor {
        val ref = Array<Any?>(INDEXABLES_XML_RES_COLUMNS.size) { null }
        ref[COLUMN_INDEX_XML_RES_RANK] = 1
        ref[COLUMN_INDEX_XML_RES_RESID] = R.xml.settings
        ref[COLUMN_INDEX_XML_RES_CLASS_NAME] = SettingsActivity::class.java.name
        ref[COLUMN_INDEX_XML_RES_INTENT_ACTION] = Intent.ACTION_MAIN
        ref[COLUMN_INDEX_XML_RES_INTENT_TARGET_PACKAGE] =
            context?.applicationInfo?.packageName
        ref[COLUMN_INDEX_XML_RES_INTENT_TARGET_CLASS] = SettingsActivity::class.java.name
        return MatrixCursor(INDEXABLES_XML_RES_COLUMNS).apply { addRow(ref) }
    }

    override fun queryRawData(projection: Array<String>?) =
        MatrixCursor(INDEXABLES_RAW_COLUMNS)

    override fun queryNonIndexableKeys(projection: Array<String>?) =
        MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)
}
