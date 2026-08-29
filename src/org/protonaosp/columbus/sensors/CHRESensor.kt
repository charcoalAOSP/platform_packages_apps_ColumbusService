/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0
 */

package org.protonaosp.columbus.sensors

import android.content.Context
import android.hardware.location.ContextHubClient
import android.hardware.location.ContextHubClientCallback
import android.hardware.location.ContextHubManager
import android.hardware.location.NanoAppMessage
import android.os.Handler
import com.google.protobuf.nano.MessageNano
import org.protonaosp.columbus.TAG
import org.protonaosp.columbus.actions.*
import org.protonaosp.columbus.dlog
import org.protonaosp.columbus.proto.nano.ContextHubMessages

private const val NANOAPP_ID = 0x476f6f676c001019L

class CHRESensor(val context: Context, var sensitivity: Float, val handler: Handler) :
    ColumbusSensor() {
    private var contextHubManager: ContextHubManager? = null
    private var callback: CHRECallback? = null
    @Volatile private var isListening: Boolean = false

    init {
        contextHubManager =
            context.getSystemService(Context.CONTEXTHUB_SERVICE) as? ContextHubManager
        callback = CHRECallback()
    }

    inner class CHRECallback : ContextHubClientCallback() {
        private var client: ContextHubClient? = null

        override fun onMessageFromNanoApp(client: ContextHubClient, msg: NanoAppMessage) {
            // Ignore other nanoapps
            if (msg.nanoAppId != NANOAPP_ID) {
                return
            }

            when (msg.messageType) {
                ContextHubMessages.GESTURE_DETECTED -> {
                    val detectedMsg =
                        ContextHubMessages.GestureDetected.parseFrom(msg.messageBody).gestureType
                    val gestureMsg = protoGestureTypeToGesture(detectedMsg)
                    if (gestureMsg == 0) return
                    handler.post { reportGestureDetected(gestureMsg) }
                }

                // Fallback for other unexpected messages
                else -> dlog(TAG, "Received unknown message of type ${msg.messageType}: $msg")
            }
        }

        override fun onNanoAppAborted(client: ContextHubClient, nanoappId: Long, error: Int) {
            if (nanoappId == NANOAPP_ID) {
                dlog(TAG, "Columbus CHRE nanoapp aborted: $error")
            }
        }

        fun setListening(listening: Boolean) {
            synchronized(this@CHRESensor) {
                if (listening) {
                    val contextHubManager = contextHubManager ?: return
                    val callback = callback ?: return
                    if (client == null) {
                        val hubs = contextHubManager.contextHubs
                        if (hubs.isNullOrEmpty()) {
                            dlog(TAG, "No context hubs found")
                            return
                        }
                        client = contextHubManager.createClient(hubs[0], callback)
                    }

                    val msg = ContextHubMessages.RecognizerStart()
                    // Only report events to AP if gesture is halfway done
                    msg.sensitivity = sensitivity

                    sendNanoappMsg(ContextHubMessages.RECOGNIZER_START, MessageNano.toByteArray(msg))
                    this@CHRESensor.isListening = true
                } else {
                    sendNanoappMsg(ContextHubMessages.RECOGNIZER_STOP, ByteArray(0))
                    client?.close()
                    client = null
                    this@CHRESensor.isListening = false
                }
            }
        }

        fun updateSensitivity() {
            synchronized(this@CHRESensor) {
                val msg = ContextHubMessages.SensitivityUpdate()
                msg.sensitivity = sensitivity
                sendNanoappMsg(ContextHubMessages.SENSITIVITY_UPDATE, MessageNano.toByteArray(msg))
            }
        }

        private fun sendNanoappMsg(msgType: Int, bytes: ByteArray) {
            val message = NanoAppMessage.createMessageToNanoApp(NANOAPP_ID, msgType, bytes)
            val ret = client?.sendMessageToNanoApp(message) ?: return
            if (ret != 0) {
                dlog(TAG, "Failed to send message of type $msgType to nanoapp: $ret")
            }
        }
    }

    private fun protoGestureTypeToGesture(protoGesture: Int): Int {
        return when (protoGesture) {
            1,
            2 -> protoGesture
            else -> 0
        }
    }

    override fun isListening(): Boolean {
        return isListening
    }

    override fun startListening() {
        callback?.setListening(true)
    }

    override fun stopListening() {
        callback?.setListening(false)
    }

    override fun updateSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity
        callback?.updateSensitivity()
    }
}
