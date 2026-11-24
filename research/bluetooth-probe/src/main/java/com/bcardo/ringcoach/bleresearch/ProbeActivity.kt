package com.bcardo.ringcoach.bleresearch

class ProbeActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(android.widget.TextView(this@ProbeActivity).apply {
                text = "Ring Bluetooth research\n\nRecords Ring replies locally for up to 18 hours. It preserves pairing and does not acknowledge or clear health history.\n\nA visible notification indicates recording."
            })
            addView(android.widget.Button(this@ProbeActivity).apply {
                text = "Start overnight recording"
                setOnClickListener { startCapture() }
            })
            addView(android.widget.Button(this@ProbeActivity).apply {
                text = "Stop recording"
                setOnClickListener { stopService(android.content.Intent(this@ProbeActivity, RingCaptureService::class.java)) }
            })
        })
        if (intent.getBooleanExtra("start_capture", false)) startCapture()
        if (intent.getBooleanExtra("stop_capture", false)) stopService(android.content.Intent(this, RingCaptureService::class.java))
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("start_capture", false)) startCapture()
        if (intent.getBooleanExtra("stop_capture", false)) stopService(android.content.Intent(this, RingCaptureService::class.java))
    }
    private fun startCapture() {
        startForegroundService(android.content.Intent(this, RingCaptureService::class.java))
    }
}
