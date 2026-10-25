package com.example

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Test-build-only diagnostics for crashes that cannot be reproduced in local automation. */
class SandboxElsewhereApplication : ElsewhereApplication() {
    override fun onCreate() {
        val diagnostics = SandboxCrashDiagnostics(this)
        diagnostics.installCrashHandler()
        super.onCreate()
        diagnostics.showStoredReportAfterRelaunch()
    }
}

internal class SandboxCrashDiagnostics(private val application: Application) {
    private val preferences by lazy {
        application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                preferences.edit()
                    .putString(REPORT_KEY, SandboxCrashReportFormatter.format(throwable))
                    .commit()
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun showStoredReportAfterRelaunch() {
        val displayed = AtomicBoolean(false)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!displayed.compareAndSet(false, true)) return
                val report = preferences.getString(REPORT_KEY, null) ?: return
                preferences.edit().remove(REPORT_KEY).apply()
                AlertDialog.Builder(activity)
                    .setTitle("Elsewhere Test crash report")
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private companion object {
        const val PREFERENCES_NAME = "sandbox_crash_diagnostics"
        const val REPORT_KEY = "last_fatal_report"
    }
}

internal object SandboxCrashReportFormatter {
    private const val MAX_CAUSES = 3
    private const val MAX_FRAMES_PER_CAUSE = 12

    fun format(throwable: Throwable): String = buildString {
        appendLine("A fatal error occurred in Elsewhere Test.")
        appendLine("This report contains exception types and code locations only.")
        var current: Throwable? = throwable
        var causeIndex = 0
        val seen = mutableSetOf<Throwable>()
        while (current != null && causeIndex < MAX_CAUSES && seen.add(current)) {
            appendLine()
            appendLine(if (causeIndex == 0) "Exception: ${safeClassName(current)}" else "Caused by: ${safeClassName(current)}")
            current.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
                appendLine("  at ${safeFrame(frame)}")
            }
            current = current.cause
            causeIndex += 1
        }
    }.trim()

    private fun safeClassName(throwable: Throwable): String =
        throwable.javaClass.name.replace(UNSAFE_CHARACTERS, "?").take(200)

    private fun safeFrame(frame: StackTraceElement): String = buildString {
        append(frame.className.replace(UNSAFE_CHARACTERS, "?").take(200))
        append('.')
        append(frame.methodName.replace(UNSAFE_CHARACTERS, "?").take(120))
        append('(')
        append(frame.fileName?.replace(UNSAFE_CHARACTERS, "?")?.take(120) ?: "Unknown Source")
        if (frame.lineNumber >= 0) append(":${frame.lineNumber}")
        append(')')
    }

    private val UNSAFE_CHARACTERS = Regex("[^A-Za-z0-9_.$<>/-]")
}
