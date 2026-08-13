package com.fetch.demo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder host for manual verification while the engine is built out. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "fetch" })
    }
}
