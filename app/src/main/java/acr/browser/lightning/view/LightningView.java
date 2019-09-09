package acr.browser.lightning.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebSettings.PluginState;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import com.cliqz.browser.R;
import com.cliqz.browser.antiphishing.AntiPhishing;
import com.cliqz.browser.app.BrowserApp;
import com.cliqz.browser.main.FlavoredActivityComponent;
import com.cliqz.browser.main.TabsManager;
import com.cliqz.browser.purchases.PurchasesManager;
import com.cliqz.browser.telemetry.Telemetry;
import com.cliqz.browser.utils.BloomFilterUtils;
import com.cliqz.browser.utils.LazyString;
import com.cliqz.browser.utils.PasswordManager;
import com.cliqz.browser.utils.WebViewPersister;
import com.cliqz.jsengine.Adblocker;
import com.cliqz.jsengine.AntiTracking;
import com.cliqz.jsengine.Engine;
import com.cliqz.jsengine.EngineNotYetAvailable;
import com.cliqz.jsengine.Insights;
import com.cliqz.nove.Bus;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import acr.browser.lightning.constant.Constants;
import acr.browser.lightning.database.HistoryDatabase;
import acr.browser.lightning.dialog.LightningDialogBuilder;
import acr.browser.lightning.download.LightningDownloadListener;
import acr.browser.lightning.preference.PreferenceManager;
import timber.log.Timber;

/**
 * @author Anthony C. Restaino
 * @author Stefano Pacifici
 * @author Ravjit Uppal
 */
public class LightningView extends FrameLayout {

    private WeakReference<WebView> mReaderModeWebViewRef = new WeakReference<>(null);

    public interface LightingViewListener {

        void increaseAntiTrackingCounter();

        void onFavIconLoaded(Bitmap favicon);
    }

    private static final String HEADER_REQUESTED_WITH = "X-Requested-With";
    private static final String HEADER_WAP_PROFILE = "X-Wap-Profile";
    private static final String HEADER_DNT = "DNT";
    private static final Pattern USER_AGENT_PATTERN =
            Pattern.compile("(.*);\\s+wv(.*)( Version/(\\d+\\.?)+)(.*)");

    final LightningViewTitle mTitle;
    private CliqzWebView mWebView;
    private boolean mIsIncognitoTab;
    private static final int API = android.os.Build.VERSION.SDK_INT;
    // private String mId;
    private boolean mIsAutoForgetTab;
    private boolean urlSSLError = false;
    /**
     * This prevent history point creation when navigating back and forward. It's used by {@link
     * LightningView} and {@link LightningChromeClient} in combination: the first set it to false
     * when navigation back or forward, the latter reset it at the end of {@link
     * LightningChromeClient#onReceivedTitle(WebView, String)}
     */
    boolean isHistoryItemCreationEnabled = true;

    private final Map<String, String> mRequestHeaders = new ArrayMap<>();

    //Id of the current page in the history database
    long historyId = -1;

    LightingViewListener lightingViewListenerListener;
    private final LazyString readabilityScript;
    private String mReaderModeContent;

    void setReaderModeHTML(@NonNull String html) {
        mReaderModeContent = html;
    }

    @Inject
    Activity activity;

    @Inject
    Bus eventBus;

    @Inject
    PreferenceManager preferences;

    @Inject
    PurchasesManager purchasesManager;

    @Inject
    LightningDialogBuilder dialogBuilder;

    @Inject
    Engine jsengine;

    @Inject
    AntiTracking attrack;

    @Inject
    Adblocker adblocker;

    @Inject
    Insights insights;

    @Inject
    HistoryDatabase historyDatabase;

    @Inject
    Telemetry telemetry;

    @Inject
    PasswordManager passwordManager;

    @Inject
    AntiPhishing antiPhishing;

    @Inject
    BloomFilterUtils bloomFilterUtils;

    @Inject
    WebViewPersister persister;

    @Inject
    TabsManager tabsManager;

    public LightningView(@NonNull Context context) {
        this(context, null);
    }

    public LightningView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LightningView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final FlavoredActivityComponent component = BrowserApp.getActivityComponent(context);
        readabilityScript = LazyString.fromRawResource(context.getResources(), R.raw.readability);
        if (component != null) {
            component.inject(this);
        }
        // mId = RelativelySafeUniqueId.createNewUniqueId();
        mIsIncognitoTab = false;
        mTitle = new LightningViewTitle(context, false);
        createWebView();
        addView(mWebView);
    }

    /**
     * Initialize the preference driven settings of the WebView
     *
     * @param settings the WebSettings object to use, you can pass in null
     *                 if you don't have a reference to them
     */
    @SuppressLint({"NewApi", "SetJavaScriptEnabled"})
    private synchronized void initializePreferences(@Nullable WebSettings settings) {
        if (settings == null && mWebView == null) {
            return;
        } else if (settings == null) {
            settings = mWebView.getSettings();
        }

        settings.setDefaultTextEncodingName("UTF-8");

        if (preferences.getDoNotTrackEnabled()) {
            mRequestHeaders.put(HEADER_DNT, "1");
        } else {
            mRequestHeaders.remove(HEADER_DNT);
        }

        if (preferences.getRemoveIdentifyingHeadersEnabled()) {
            mRequestHeaders.put(HEADER_REQUESTED_WITH, "");
            mRequestHeaders.put(HEADER_WAP_PROFILE, "");
        } else {
            mRequestHeaders.remove(HEADER_REQUESTED_WITH);
            mRequestHeaders.remove(HEADER_WAP_PROFILE);
        }

        settings.setGeolocationEnabled(!mIsIncognitoTab);

        if (API < Build.VERSION_CODES.KITKAT) {
            switch (preferences.getFlashSupport()) {
                case 0:
                    //noinspection deprecation
                    settings.setPluginState(PluginState.OFF);
                    break;
                case 1:
                    //noinspection deprecation
                    settings.setPluginState(PluginState.ON_DEMAND);
                    break;
                case 2:
                    //noinspection deprecation
                    settings.setPluginState(PluginState.ON);
                    break;
                default:
                    break;
            }
        }

        settings.setUserAgentString(getMobileUserAgent());

        if (API <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            //noinspection deprecation
            settings.setSavePassword(false);
        }
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        if (preferences.getTextReflowEnabled()) {
            settings.setLayoutAlgorithm(LayoutAlgorithm.NARROW_COLUMNS);
            if (API >= android.os.Build.VERSION_CODES.KITKAT) {
                try {
                    settings.setLayoutAlgorithm(LayoutAlgorithm.TEXT_AUTOSIZING);
                } catch (Exception e) {
                    // This shouldn't be necessary, but there are a number
                    // of KitKat devices that crash trying to set this
                    Timber.e("Problem setting LayoutAlgorithm to TEXT_AUTOSIZING");
                }
            }
        } else {
            settings.setLayoutAlgorithm(LayoutAlgorithm.NORMAL);
        }

        // This was disabled for version 1.0.2r2, please restore if needed needed
        // settings.setBlockNetworkImage(preferences.getBlockImagesEnabled());
        if (!mIsIncognitoTab) {
            settings.setSupportMultipleWindows(preferences.getPopupsEnabled());
        } else {
            settings.setSupportMultipleWindows(false);
        }
        settings.setUseWideViewPort(preferences.getUseWideViewportEnabled());
        settings.setLoadWithOverviewMode(preferences.getOverviewModeEnabled());
        switch (preferences.getTextSize()) {
            case 0:
                settings.setTextZoom(200);
                break;
            case 1:
                settings.setTextZoom(150);
                break;
            case 2:
                settings.setTextZoom(125);
                break;
            case 3:
                settings.setTextZoom(100);
                break;
            case 4:
                settings.setTextZoom(75);
                break;
            case 5:
                settings.setTextZoom(50);
                break;
        }
        CookieManager.getInstance().setAcceptCookie(preferences.getCookiesEnabled() && !isIncognitoTab());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(mWebView, false);
        }

        // update jsengine module states.
        try {
            attrack.setEnabled(purchasesManager.isDashboardEnabled() && preferences.isAttrackEnabled());
            adblocker.setEnabled(purchasesManager.isDashboardEnabled() && preferences.getAdBlockEnabled());
            insights.setEnabled(purchasesManager.isDashboardEnabled() && preferences.isAttrackEnabled());

        } catch (EngineNotYetAvailable e) {
            Timber.w(e, "error updating jsengine state");
        }

    }

    /**
     * Initialize the settings of the WebView that are intrinsic to Lightning and cannot
     * be altered by the user. Distinguish between Incognito and Regular tabs here.
     *
     * @param settings the WebSettings object to use.
     * @param context  the Context which was used to construct the WebView.
     */
    @SuppressLint("NewApi")
    private void initializeSettings(WebSettings settings, Context context) {
        if (API < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            //noinspection deprecation
            settings.setAppCacheMaxSize(Long.MAX_VALUE);
        }
        if (API < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            //noinspection deprecation
            settings.setEnableSmoothTransition(true);
        }
        if (API > Build.VERSION_CODES.JELLY_BEAN) {
            settings.setMediaPlaybackRequiresUserGesture(true);
        }
        if (API >= Build.VERSION_CODES.LOLLIPOP && !mIsIncognitoTab) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        } else if (API >= Build.VERSION_CODES.LOLLIPOP) {
            // We're in Incognito mode, reject
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (!mIsIncognitoTab) {
            settings.setDomStorageEnabled(true);
            settings.setAppCacheEnabled(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setDatabaseEnabled(true);
        } else {
            settings.setDomStorageEnabled(false);
            settings.setAppCacheEnabled(false);
            settings.setDatabaseEnabled(false);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setDefaultTextEncodingName("utf-8");
        // setAccessFromUrl(urlView, settings);
        if (API >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }

        settings.setAppCachePath(context.getDir("appcache", 0).getPath());
        settings.setGeolocationDatabasePath(context.getDir("geolocation", 0).getPath());
        if (API < Build.VERSION_CODES.KITKAT) {
            //noinspection deprecation
            settings.setDatabasePath(context.getDir("databases", 0).getPath());
        }
    }

    /**
     * If reader content is available, display the reader mode. It creates the reader webview
     * if needed.
     */
    public void readerMode() {
        if (mReaderModeContent == null || mReaderModeContent.isEmpty()) {
            return;
        }
        try {
            // Just use the string instead of the Charset object in case we want to support API < 19
            @SuppressWarnings("CharsetObjectCanBeUsed")
            final String contentBase64 = Base64.encodeToString(
                    mReaderModeContent.getBytes("UTF-8"), Base64.NO_WRAP);
            WebView readerWebView = mReaderModeWebViewRef.get();
            if (readerWebView == null) {
                readerWebView = new CliqzWebView(getContext());
                readerWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        webMode();
                        loadUrl(url);
                        return true;
                    }
                });
                mReaderModeWebViewRef = new WeakReference<>(readerWebView);
            }
            removeView(mWebView);
            addView(readerWebView);
            readerWebView.loadData(contentBase64, "text/html; charset=utf-8", "base64");
        } catch (UnsupportedEncodingException e) {
            Timber.e(e, "Invalid encoding: UTF-8");
        }
    }

    /**
     * Display the web page
     */
    public void webMode() {
        removeAllViews();
        addView(mWebView);
    }

    public void setUrlSSLError(boolean urlSSLError) {
        this.urlSSLError = urlSSLError;
    }

    public boolean isUrlSSLError() {
        return urlSSLError;
    }

    public void onPause() {
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    void injectReadabilityScript() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(readabilityScript.getString(),
                    new ReadabilityCallback(this));
        }
    }

    public void setTransportWebView(@NonNull WebView.WebViewTransport transport) {
        if (mWebView != null) {
            transport.setWebView(mWebView);
        }
    }

    public int webViewHashCode() {
        return mWebView != null ? mWebView.hashCode() : 0;
    }

    public void setWebViewAnimation(Animation animation) {
        if (mWebView != null) {
            mWebView.setAnimation(animation);
        }
    }

    @Nullable
    public String getUserAgentString() {
        if (mWebView == null) {
            return null;
        } else {
            return mWebView.getSettings().getUserAgentString();
        }
    }

    public synchronized void onResume() {
        if (mWebView != null) {
            Timber.w("Resuming");
            initializePreferences(mWebView.getSettings());
            mWebView.onResume();
        }
    }

    public synchronized void stopLoading() {
        if (mWebView != null) {
            mWebView.stopLoading();
        }
    }

    public synchronized void resumeTimers() {
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    public void setVisibility(int visible) {
        if (mWebView != null) {
            mWebView.setVisibility(visible);
        }
    }

    public synchronized void reload() {
        if (mWebView != null) {
            isHistoryItemCreationEnabled = false;
            mWebView.reload();
        }
    }

    @SuppressLint("NewApi")
    public synchronized void findInPage(String text) {
        if (mWebView != null) {
            mWebView.findAllAsync(text);
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    public synchronized void onDestroy() {
        if (mWebView != null) {
            //deletePreview();
            // Check to make sure the WebView has been removed
            // before calling destroy() so that a memory leak is not created
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.stopLoading();
            mWebView.onPause();
            mWebView.clearHistory();
            mWebView.setVisibility(View.GONE);
            mWebView.removeAllViews();
            mWebView.destroyDrawingCache();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                //this is causing the segfault occasionally below 4.2
                mWebView.destroy();
            }
            mWebView = null;
        }
    }

    public synchronized void goBack() {
        if (mWebView != null) {
            isHistoryItemCreationEnabled = false;
            mWebView.goBack();
        }
    }

    public synchronized void goForward() {
        if (mWebView != null) {
            isHistoryItemCreationEnabled = false;
            mWebView.goForward();
        }
    }

    public synchronized void findNext() {
        if (mWebView != null) {
            mWebView.findNext(true);
        }
    }

    public synchronized void findPrevious() {
        if (mWebView != null) {
            mWebView.findNext(false);
        }
    }

    public boolean canGoBack() {
        return mWebView != null && mWebView.canGoBack();
    }

    public boolean canGoForward() {
        return mWebView != null && mWebView.canGoForward();
    }

    private void createWebView() {
        final Context context = getContext();
        mWebView = new CliqzWebView(context);
        mWebView.setDrawingCacheBackgroundColor(Color.WHITE);
        mWebView.setFocusableInTouchMode(true);
        mWebView.setFocusable(true);
        mWebView.setDrawingCacheEnabled(false);
        mWebView.setWillNotCacheDrawing(true);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            mWebView.setAnimationCacheEnabled(false);
            mWebView.setAlwaysDrawnWithCacheEnabled(false);
        }
        mWebView.setBackgroundColor(Color.WHITE);

        mWebView.setSaveEnabled(true);
        mWebView.setNetworkAvailable(true);
        mWebView.setWebChromeClient(new LightningChromeClient(activity, this));
        mWebView.setWebViewClient(new LightningWebClient(context, this));
        mWebView.setDownloadListener(new LightningDownloadListener(context));
        initializeSettings(mWebView.getSettings(), context);
    }

    public void restoreTab(@NonNull String tabId) {
        persister.restore(tabId, mWebView);
    }

    public Bitmap getFavicon() {
        return mTitle.getFavicon();
    }

    public synchronized void loadUrl(String url) {
        if (mWebView != null && url != null) {
            mWebView.loadUrl(url, mRequestHeaders);
        }
    }

    public String getTitle() {
        return mTitle.getTitle();
    }

    @NonNull
    public String getUrl() {
        if (mWebView != null && mWebView.getUrl() != null) {
            return mWebView.getUrl();
        } else {
            return "";
        }
    }

    public boolean isIncognitoTab() {
        return mIsIncognitoTab;
    }

    public void setIsIncognitoTab(boolean isIncognitoTab) {
        this.mIsIncognitoTab = isIncognitoTab;
    }

    boolean isAutoForgetTab() {
        return mIsAutoForgetTab;
    }

    public void setIsAutoForgetTab(boolean isAutoForgetTab) {
        this.mIsAutoForgetTab = isAutoForgetTab;
    }

    public void enableAdBlock() {
        try {
            adblocker.setEnabled(true);
        } catch (EngineNotYetAvailable engineNotYetAvailable) {
            engineNotYetAvailable.printStackTrace();
        }
    }

    public void enableAttrack(){
        try {
            attrack.setEnabled(true);
            insights.setEnabled(true);
        } catch (EngineNotYetAvailable engineNotYetAvailable) {
            engineNotYetAvailable.printStackTrace();
        }
    }

    public void setDesktopUserAgent() {
        if (mWebView == null) {
            return;
        }
        final WebSettings webSettings = mWebView.getSettings();
        webSettings.setUserAgentString(Constants.DESKTOP_USER_AGENT);
        final String url = mWebView.getUrl();
        if (url != null && (
                (url.startsWith("m.") || url.contains("/m.")))) {
            mWebView.loadUrl(url.replaceFirst("m.", ""));
        } else {
            mWebView.reload();
        }
    }

    public void setMobileUserAgent() {
        if (mWebView == null) {
            return;
        }

        final WebSettings webSettings = mWebView.getSettings();
        webSettings.setUserAgentString(getMobileUserAgent());
        mWebView.reload();
    }

    private String getMobileUserAgent() {
        final String defaultUserAgent = mWebView.getSettings().getUserAgentString();
        final Matcher matcher = USER_AGENT_PATTERN.matcher(defaultUserAgent);
        final String userAgent;
        if (matcher.matches() && matcher.groupCount() >= 5) {
            userAgent = matcher.group(1) + matcher.group(2) + matcher.group(5);
        } else {
            userAgent = defaultUserAgent;
        }
        return userAgent;
    }

    void addItemToHistory(@Nullable final String title, @NonNull final String url) {
        if (!url.startsWith(Constants.FILE)) {
            LightningView.this.historyId = historyDatabase.visitHistoryItem(url, title);
        }
    }

    void updateHistoryItemTitle(@NonNull final String title) {
        if (historyId < 0) {
            return;
        }
        historyDatabase.updateTitleFor(historyId, title);
    }

    public void setListener(LightingViewListener lightingViewListenerListener) {
        this.lightingViewListenerListener = lightingViewListenerListener;
    }

    public boolean isUrlWhiteListed(){
        final Uri uri = Uri.parse(getUrl());
        final String host = uri.getHost();
        return attrack.isWhitelisted(host);
    }
}
