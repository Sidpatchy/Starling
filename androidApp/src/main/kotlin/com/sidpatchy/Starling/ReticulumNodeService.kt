package com.sidpatchy.Starling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors
import android.util.Base64

/**
 * Dedicated-process, user-visible owner of the embedded RNS runtime.
 * RNS registers process-level signal handlers and owns long-lived worker
 * threads, so it must not share the Compose Activity's process lifecycle.
 */
class ReticulumNodeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val rrcWorker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "starling-rrc") }
    private var mode = ReticulumRuntimeMode.EMBEDDED
    private var tcpPeersJson = "[]"
    private var rnodeInterfacesJson = "[]"
    private var rpcKeyHex = ""
    private val rnodeBluetoothBridge by lazy { AndroidRNodeBluetoothBridge(this) }
    private var pythonModule: com.chaquo.python.PyObject? = null
    private var rrcModule: com.chaquo.python.PyObject? = null
    private var lxmfModule: com.chaquo.python.PyObject? = null
    private var lastPublishedSelfEventLine: String? = null
    private var lastNodeState = STATE_STOPPED
    private var lastNodeMessage = ""
    private var lastNodeShared = false
    private var lastNodeTcpStatusJson = "[]"
    private var lastNodeRNodeStatusJson = "[]"
    @Volatile private var stopping = false

    private val pollStatus = object : Runnable {
        override fun run() {
            publishRuntimeStatus()
            if (!stopping) handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    private val pollRrcEvents = object : Runnable {
        override fun run() {
            drainRrcEvents()
            drainLxmfEvents()
            if (!stopping) handler.postDelayed(this, RRC_EVENT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopNode(startId)
            ACTION_START -> startNode(intent, startId)
            ACTION_NODE_STATUS -> {
                if (stopping || lastNodeState == STATE_STARTING) {
                    publish(STATE_STARTING, message = lastNodeMessage)
                } else if (pythonModule != null) {
                    publishRuntimeStatus()
                } else {
                    publish(lastNodeState, lastNodeMessage, lastNodeShared, lastNodeTcpStatusJson, lastNodeRNodeStatusJson)
                    if (lastNodeState == STATE_STOPPED || lastNodeState == STATE_FAILED) stopSelf(startId)
                }
            }
            ACTION_RRC_CONNECT, ACTION_RRC_DISCONNECT, ACTION_RRC_JOIN, ACTION_RRC_PART, ACTION_RRC_SEND ->
                handleRrcCommand(intent)
            ACTION_LXMF_SEND, ACTION_LXMF_SET_NAME -> handleLxmfCommand(intent)
            ACTION_RRC_STATUS -> publishRrcCurrentState()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startNode(intent: Intent, startId: Int) {
        mode = runCatching {
            ReticulumRuntimeMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty())
        }.getOrDefault(ReticulumRuntimeMode.EMBEDDED)
        tcpPeersJson = intent.getStringExtra(EXTRA_TCP_PEERS).orEmpty().ifBlank { "[]" }
        rnodeInterfacesJson = intent.getStringExtra(EXTRA_RNODE_INTERFACES).orEmpty().ifBlank { "[]" }
        rpcKeyHex = intent.getStringExtra(EXTRA_RPC_KEY).orEmpty()

        startForegroundCompat()
        publish(STATE_STARTING)

        rrcWorker.execute {
            try {
                startBleRNodeBridgeIfNeeded()
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val module = Python.getInstance().getModule("starling_rns")
                pythonModule = module
                rrcModule = Python.getInstance().getModule("starling_rrc")
                lxmfModule = Python.getInstance().getModule("starling_lxmf")
                val result = module.callAttr(
                    "start_node",
                    noBackupFilesDir.resolve("reticulum").absolutePath,
                    if (mode == ReticulumRuntimeMode.EMBEDDED) "embedded" else "existing_shared_instance",
                    tcpPeersJson,
                    rpcKeyHex,
                    rnodeInterfacesJson,
                ).toString()
                try {
                    lxmfModule?.callAttr("start", noBackupFilesDir.resolve("reticulum").absolutePath)
                } catch (error: Exception) {
                    Log.w(TAG, "LXMF could not start", error)
                    handler.post { publishLxmfError(error.message ?: "Could not start LXMF.") }
                }
                val runtimeStatus = JSONObject(result)
                handler.post {
                    publishRuntimeStatus(runtimeStatus)
                    handler.removeCallbacks(pollStatus)
                    handler.removeCallbacks(pollRrcEvents)
                    handler.postDelayed(pollStatus, STATUS_INTERVAL_MS)
                    handler.postDelayed(pollRrcEvents, RRC_EVENT_INTERVAL_MS)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Reticulum startup failed", error)
                rnodeBluetoothBridge.stop()
                handler.post {
                    publish(STATE_FAILED, message = error.message ?: error.javaClass.simpleName)
                    handler.removeCallbacks(pollStatus)
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
        }
    }

    private fun stopNode(startId: Int) {
        stopping = true
        handler.removeCallbacks(pollStatus)
        handler.removeCallbacks(pollRrcEvents)
        rrcWorker.execute {
            try {
                rrcModule?.callAttr("disconnect")
                drainRrcEvents()
                lxmfModule?.callAttr("stop")
                drainLxmfEvents()
                pythonModule?.callAttr("stop_node")
            } catch (error: Exception) {
                Log.w(TAG, "Error while shutting down Reticulum", error)
            } finally {
                rnodeBluetoothBridge.stop()
            }
            handler.post {
                publish(STATE_STOPPED)
                publishRrcEventLine("LOCAL_ADDRESS\t")
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
    }

    private fun publishRuntimeStatus(existing: JSONObject? = null) {
        try {
            val json = existing ?: pythonModule?.callAttr("status")?.toString()?.let(::JSONObject)
            if (json == null || !json.optBoolean("running", false)) {
                publish(STATE_STOPPED)
                return
            }
            val shared = json.optBoolean("shared_instance_connected", false)
            val tcpStatusJson = json.optJSONArray("tcp_interfaces")?.toString() ?: "[]"
            val rnodeStatusJson = json.optJSONArray("rnode_interfaces")?.toString() ?: "[]"
            val peers = json.optJSONArray("tcp_interfaces")
            val radios = json.optJSONArray("rnode_interfaces")
            var onlineCount = 0
            if (peers != null) {
                for (index in 0 until peers.length()) {
                    if (peers.optJSONObject(index)?.optBoolean("online", false) == true) onlineCount++
                }
            }
            var onlineRadioCount = 0
            if (radios != null) {
                for (index in 0 until radios.length()) {
                    if (radios.optJSONObject(index)?.optBoolean("online", false) == true) onlineRadioCount++
                }
            }
            publish(
                state = STATE_RUNNING,
                shared = shared,
                tcpStatusJson = tcpStatusJson,
                rnodeStatusJson = rnodeStatusJson,
            )
            val notificationText = when {
                shared -> "Using the existing local Reticulum instance"
                radios != null && radios.length() > 0 && peers != null && peers.length() > 0 ->
                    "$onlineCount TCP and $onlineRadioCount RNode connections online"
                radios != null && radios.length() > 0 -> "$onlineRadioCount of ${radios.length()} RNode radios online"
                peers != null && peers.length() > 0 -> "$onlineCount of ${peers.length()} TCP connections online"
                else -> "Reticulum node is running"
            }
            updateForegroundNotification(notificationText)
            publishRrcCurrentState()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read Reticulum status", error)
            publish(STATE_FAILED, message = error.message ?: "Could not read Reticulum status.")
        }
    }

    private fun handleRrcCommand(intent: Intent) {
        val module = rrcModule
        if (module == null) {
            publishRrcError("Start the Reticulum node before connecting to an RRC hub.")
            return
        }
        rrcWorker.execute {
            try {
                when (intent.action) {
                    ACTION_RRC_CONNECT -> {
                        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                        module.callAttr("connect", intent.getStringExtra(EXTRA_HUB_DESTINATION).orEmpty(), displayName)
                    }
                    ACTION_RRC_DISCONNECT -> module.callAttr("disconnect")
                    ACTION_RRC_JOIN -> module.callAttr("join", intent.getStringExtra(EXTRA_ROOM).orEmpty())
                    ACTION_RRC_PART -> module.callAttr("part", intent.getStringExtra(EXTRA_ROOM).orEmpty())
                    ACTION_RRC_SEND -> module.callAttr(
                        "send",
                        intent.getStringExtra(EXTRA_ROOM).orEmpty(),
                        intent.getStringExtra(EXTRA_BODY).orEmpty(),
                    intent.getStringExtra(EXTRA_ACTION).toBoolean(),
                    )
                }
                drainRrcEvents()
            } catch (error: Exception) {
                Log.w(TAG, "RRC command failed", error)
                publishRrcError(error.message ?: "RRC command failed.")
            }
        }
    }

    private fun handleLxmfCommand(intent: Intent) {
        val module = lxmfModule
        if (module == null) {
            publishLxmfError("Start the Reticulum node before sending LXMF messages.")
            return
        }
        rrcWorker.execute {
            try {
                if (intent.action == ACTION_LXMF_SET_NAME) {
                    module.callAttr("set_display_name", intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty())
                } else {
                    module.callAttr(
                        "send_direct",
                        intent.getStringExtra(EXTRA_LXMF_ADDRESS).orEmpty(),
                        intent.getStringExtra(EXTRA_BODY).orEmpty(),
                    )
                }
                drainLxmfEvents()
            } catch (error: Exception) {
                Log.w(TAG, "LXMF send failed", error)
                publishLxmfError(error.message ?: "Could not send the LXMF message.")
            }
        }
    }

    private fun drainRrcEvents() {
        try {
            val lines = rrcModule?.callAttr("poll_event_lines")?.toString().orEmpty()
            lines.lineSequence().filter { it.isNotBlank() }.forEach(::publishRrcEventLine)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read RRC events", error)
        }
    }

    private fun drainLxmfEvents() {
        try {
            val lines = lxmfModule?.callAttr("poll_event_lines")?.toString().orEmpty()
            lines.lineSequence().filter { it.isNotBlank() }.forEach(::publishRrcEventLine)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read LXMF events", error)
        }
    }

    private fun publishRrcCurrentState() {
        try {
            rrcModule?.callAttr("current_connection_event_line")?.toString()?.let(::publishRrcEventLine)
            rrcModule?.callAttr("current_self_event_line")?.toString()?.let { selfLine ->
                if (selfLine != lastPublishedSelfEventLine) {
                    lastPublishedSelfEventLine = selfLine
                    publishRrcEventLine(selfLine)
                }
            }
            lxmfModule?.callAttr("current_local_address_event_line")?.toString()?.let(::publishRrcEventLine)
            lxmfModule?.callAttr("current_identity_event_line")?.toString()?.let(::publishRrcEventLine)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read RRC connection state", error)
        }
    }

    private fun publishRrcError(message: String) {
        val encoded = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        publishRrcEventLine("ERROR\t$encoded\t")
    }

    private fun publishLxmfError(message: String) {
        val encoded = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        publishRrcEventLine("LXMF_ERROR\t$encoded\t")
    }

    private fun publishRrcEventLine(line: String) {
        sendBroadcast(
            Intent(ACTION_RRC_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_RRC_EVENT, line),
        )
    }

    private fun publish(
        state: String,
        message: String = "",
        shared: Boolean = false,
        tcpStatusJson: String = "[]",
        rnodeStatusJson: String = "[]",
    ) {
        lastNodeState = state
        lastNodeMessage = message
        lastNodeShared = shared
        lastNodeTcpStatusJson = tcpStatusJson
        lastNodeRNodeStatusJson = rnodeStatusJson
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_MODE, mode.name)
            .putExtra(EXTRA_TCP_STATUS, tcpStatusJson)
            .putExtra(EXTRA_RNODE_STATUS, rnodeStatusJson)
            .putExtra(EXTRA_SHARED, shared)
            .putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(intent)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reticulum connection", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = notificationBuilder()
            .setContentTitle("Starling Reticulum node")
            .setContentText("Starting network connection…")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(text: String) {
        val notification = notificationBuilder()
            .setContentTitle("Starling Reticulum node")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacks(pollStatus)
        handler.removeCallbacks(pollRrcEvents)
        runCatching { rnodeBluetoothBridge.stop() }
        // This service has its own process. Reticulum is a process singleton;
        // clearing that interpreter on shutdown makes a later user reconnect
        // start from a clean state without killing the UI process.
        runCatching { pythonModule?.callAttr("stop_node") }
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun startBleRNodeBridgeIfNeeded() {
        val radios = JSONArray(rnodeInterfacesJson)
        var selectedName: String? = null
        for (index in 0 until radios.length()) {
            val port = radios.optJSONObject(index)?.optString("port").orEmpty()
            if (!port.startsWith("ble://", ignoreCase = true)) continue
            if (selectedName != null) throw IllegalArgumentException("Only one BLE RNode can be connected on Android at a time.")
            selectedName = port.drop("ble://".length).trim()
        }
        if (selectedName != null) {
            val error = rnodeBluetoothBridge.start(selectedName)
            if (error != null) throw IllegalStateException(error)
        }
    }

    companion object {
        const val ACTION_START = "com.sidpatchy.Starling.action.START_RETICULUM"
        const val ACTION_STOP = "com.sidpatchy.Starling.action.STOP_RETICULUM"
        const val ACTION_NODE_STATUS = "com.sidpatchy.Starling.action.NODE_STATUS"
        const val ACTION_STATE = "com.sidpatchy.Starling.action.RETICULUM_STATE"
        const val ACTION_RRC_EVENT = "com.sidpatchy.Starling.action.RRC_EVENT"
        const val ACTION_RRC_CONNECT = "com.sidpatchy.Starling.action.RRC_CONNECT"
        const val ACTION_RRC_DISCONNECT = "com.sidpatchy.Starling.action.RRC_DISCONNECT"
        const val ACTION_RRC_JOIN = "com.sidpatchy.Starling.action.RRC_JOIN"
        const val ACTION_RRC_PART = "com.sidpatchy.Starling.action.RRC_PART"
        const val ACTION_RRC_SEND = "com.sidpatchy.Starling.action.RRC_SEND"
        const val ACTION_RRC_STATUS = "com.sidpatchy.Starling.action.RRC_STATUS"
        const val ACTION_LXMF_SEND = "com.sidpatchy.Starling.action.LXMF_SEND"
        const val ACTION_LXMF_SET_NAME = "com.sidpatchy.Starling.action.LXMF_SET_NAME"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TCP_PEERS = "tcp_peers"
        const val EXTRA_TCP_STATUS = "tcp_status"
        const val EXTRA_RNODE_INTERFACES = "rnode_interfaces"
        const val EXTRA_RNODE_STATUS = "rnode_status"
        const val EXTRA_STATE = "state"
        const val EXTRA_SHARED = "shared"
        const val EXTRA_RPC_KEY = "rpc_key"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_HUB_DESTINATION = "hub_destination"
        const val EXTRA_LXMF_ADDRESS = "lxmf_address"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_BODY = "body"
        const val EXTRA_ACTION = "is_action"
        const val EXTRA_RRC_EVENT = "rrc_event"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_FAILED = "failed"
        const val STATE_STOPPED = "stopped"

        private const val TAG = "ReticulumNodeService"
        private const val CHANNEL_ID = "starling_reticulum"
        private const val NOTIFICATION_ID = 521
        private const val STATUS_INTERVAL_MS = 2_000L
        private const val RRC_EVENT_INTERVAL_MS = 200L
    }
}
