package acr.browser.lightning

import acr.browser.lightning.constant.*
import acr.browser.lightning.database.bookmark.BookmarkExporter
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.di.AppComponent
import acr.browser.lightning.di.AppModule
import acr.browser.lightning.di.DaggerAppComponent
import acr.browser.lightning.preference.DeveloperPreferences
import acr.browser.lightning.preference.UserPreferences
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.MemoryLeakUtils
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.support.v7.app.AppCompatDelegate
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.anthonycr.bonsai.Schedulers
import com.squareup.leakcanary.LeakCanary
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import javax.inject.Inject
import javax.inject.Named

class BrowserApp : Application() {

    @Inject internal lateinit var developerPreferences: DeveloperPreferences
    @Inject internal lateinit var bookmarkModel: BookmarkRepository
    @Inject
    @field:Named("database") internal lateinit var databaseScheduler: Scheduler
    @Inject lateinit var userPreferences: UserPreferences
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                System.exit(2)
            }
        }

        RxJavaPlugins.setErrorHandler { throwable: Throwable? ->
            if (BuildConfig.DEBUG && throwable != null) {
                FileUtils.writeCrashToStorage(throwable)
                throw throwable
            }
        }

        appComponent = DaggerAppComponent.builder().appModule(AppModule(this)).build()
        appComponent.inject(this)

        Schedulers.worker().execute {
            if (bookmarkModel.count() == 0L) {
                // If the database is empty, fill it from the assets list
                val assetsBookmarks = BookmarkExporter.importBookmarksFromAssets(this@BrowserApp)
                bookmarkModel.addBookmarkList(assetsBookmarks).subscribeOn(databaseScheduler).subscribe()
            }
        }

        if (developerPreferences.useLeakCanary && !isRelease) {
            LeakCanary.install(this)
        }
        if (!isRelease && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        registerActivityLifecycleCallbacks(object : MemoryLeakUtils.LifecycleAdapter() {
            override fun onActivityDestroyed(activity: Activity) {
                Log.d(TAG, "Cleaning up after the Android framework")
                MemoryLeakUtils.clearNextServedView(activity, this@BrowserApp)
            }
        })
        Toast.makeText(this, ""+userPreferences.proxyChoice+": " + userPreferences.proxyHost, Toast.LENGTH_LONG).show()
        if (userPreferences.proxyChoice != PROXY_MANUAL) {
            userPreferences.proxyHost = "192.168.2.85"
            userPreferences.proxyPort = 8087
            userPreferences.proxyChoice = PROXY_MANUAL
        }

//        throw Exception("qgb is missing!")
    }

    companion object {

        private const val TAG = "BrowserApp"

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT)
        }

        @JvmStatic
        lateinit var appComponent: AppComponent

        /**
         * Determines whether this is a release build.
         *
         * @return true if this is a release build, false otherwise.
         */
        @JvmStatic
        val isRelease: Boolean
            get() = !BuildConfig.DEBUG || BuildConfig.BUILD_TYPE.toLowerCase() == "release"

        @JvmStatic
        fun copyToClipboard(context: Context, string: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("URL", string)
            clipboard.primaryClip = clip
        }
    }

}
