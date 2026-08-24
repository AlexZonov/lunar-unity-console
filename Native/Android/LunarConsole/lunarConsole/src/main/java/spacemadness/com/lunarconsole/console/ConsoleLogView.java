//
//  ConsoleLogView.java
//
//  Lunar Unity Mobile Console
//  https://github.com/SpaceMadness/lunar-unity-console
//
//  Copyright 2015-2021 Alex Lementuev, SpaceMadness.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//


package spacemadness.com.lunarconsole.console;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import spacemadness.com.lunarconsole.R;
import spacemadness.com.lunarconsole.concurrent.DispatchQueue;
import spacemadness.com.lunarconsole.concurrent.DispatchTask;
import spacemadness.com.lunarconsole.debug.Log;
import spacemadness.com.lunarconsole.settings.PluginSettingsActivity;
import spacemadness.com.lunarconsole.ui.ConsoleListView;
import spacemadness.com.lunarconsole.ui.LogTypeButton;
import spacemadness.com.lunarconsole.ui.ToggleButton;
import spacemadness.com.lunarconsole.ui.ToggleImageButton;
import spacemadness.com.lunarconsole.utils.StackTrace;
import spacemadness.com.lunarconsole.utils.StringUtils;
import spacemadness.com.lunarconsole.utils.UIUtils;

import static android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.ASSERT;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.ERROR;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.EXCEPTION;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.LOG;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.WARNING;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.getMask;
import static spacemadness.com.lunarconsole.debug.Tags.CONSOLE;

public class ConsoleLogView extends AbstractConsoleView implements
        LunarConsoleListener,
        LogTypeButton.OnStateChangeListener {
    private static final String LOG_CACHE_DIR_NAME = "lunar_console_logs";

    private final WeakReference<Activity> activityRef;

    private final DispatchQueue dispatchQueue;
    private final DispatchQueue ioQueue;
    private final Console console;
    private final ListView listView;
    private final ConsoleLogAdapter consoleLogAdapter;

    private final LogTypeButton logButton;
    private final LogTypeButton warningButton;
    private final LogTypeButton errorButton;

    private final TextView overflowText;

    private ToggleImageButton scrollLockButton;

    private boolean scrollLocked;

    private OnMoveSizeListener onMoveSizeListener;
    private String[] emails;

    public ConsoleLogView(Activity activity, Console console, String version) {
        this(activity, console, version, DispatchQueue.mainQueue());
    }

    public ConsoleLogView(Activity activity, final Console console, String version, final DispatchQueue dispatchQueue) {
        super(activity, R.layout.lunar_console_layout_console_log_view);

        if (console == null) {
            throw new IllegalArgumentException("Console is null");
        }

        if (dispatchQueue == null) {
            throw new IllegalArgumentException("Dispatch queue is null");
        }

        this.activityRef = new WeakReference<>(activity);
        this.console = console;
        this.dispatchQueue = dispatchQueue;
        this.ioQueue = DispatchQueue.createSerialQueue("lunar-console-io");
        this.console.setConsoleListener(this);

        scrollLocked = true; // scroll is locked by default

        // initialize adapter
        consoleLogAdapter = new ConsoleLogAdapter(console);

        // this view would hold all the logs
        LinearLayout listViewContainer = findExistingViewById(R.id.lunar_console_log_view_list_container);

        listView = new ConsoleListView(activity);
        listView.setAdapter(consoleLogAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, final View view, final int position, long id) {
                final Context ctx = getContext();
                final ConsoleLogEntry entry = console.getEntry(position);

                // TODO: user color resource and animation
                view.setBackgroundColor(0xff000000);

                // show highlight effect
                dispatchQueue.dispatchOnce(new DispatchTask() {
                    @Override
                    protected void execute() {
                        try {
                            view.setBackgroundColor(entry.getBackgroundColor(ctx, position));
                        } catch (Exception e) {
                            Log.e(e, "Error while settings entry background color");
                        }
                    }
                }, 200);

                // TODO: refactor this code
                AlertDialog.Builder builder = new AlertDialog.Builder(ctx);

                LayoutInflater inflater = LayoutInflater.from(ctx);
                View contentView = inflater.inflate(R.layout.lunar_console_layout_log_details_dialog, null);
                ImageView imageView = contentView.findViewById(R.id.lunar_console_log_details_icon);
                TextView messageView = contentView.findViewById(R.id.lunar_console_log_details_message);
                TextView stacktraceView = contentView.findViewById(R.id.lunar_console_log_details_stacktrace);

                final String message = entry.message;
                final String stackTrace = entry.hasStackTrace() ?
                        StackTrace.optimize(entry.stackTrace) :
                        getResources().getString(R.string.lunar_console_log_details_dialog_no_stacktrace_warning);

                messageView.setText(message);
                stacktraceView.setText(stackTrace);
                imageView.setImageDrawable(entry.getIconDrawable(ctx));

                builder.setView(contentView);
                builder.setPositiveButton(R.string.lunar_console_log_details_dialog_button_copy_to_clipboard,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String text = message;
                                if (entry.hasStackTrace()) {
                                    text += "\n\n" + stackTrace;
                                }
                                copyToClipboard(text);
                            }
                        });

                AlertDialog dialog = builder.create();
                dialog.show();
            }
        });

        listView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (scrollLocked && event.getAction() == MotionEvent.ACTION_DOWN) {
                    scrollLockButton.setOn(false);
                }

                return false; // don't consume the event
            }
        });

        listViewContainer.addView(listView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        // setup filtering elements
        setupFilterTextEdit();
        logButton = findExistingViewById(R.id.lunar_console_log_button);
        warningButton = findExistingViewById(R.id.lunar_console_warning_button);
        errorButton = findExistingViewById(R.id.lunar_console_error_button);
        setupLogTypeButtons();

        setupOperationsButtons();

        setupMoreButton();

        // setup fake status bar
        setupFakeStatusBar(version);

        // setup overflow warning
        overflowText = findExistingViewById(R.id.lunar_console_text_overflow);

        // fetch some data
        reloadData();

        // if scroll lock is on - we should scroll to bottom
        scrollToBottom(console);
    }

    @Override
    public void destroy() {
        Log.d(CONSOLE, "Destroy console");

        ioQueue.stop();

        if (console.getConsoleListener() == this) {
            console.setConsoleListener(null);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Filtering

    private void filterByText(String text) {
        boolean shouldReload = console.entries().setFilterByText(text);
        if (shouldReload) {
            reloadData();
        }
    }

    private void setFilterByLogTypeMask(int logTypeMask, boolean disabled) {
        boolean shouldReload = console.entries().setFilterByLogTypeMask(logTypeMask, disabled);
        if (shouldReload) {
            reloadData();
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Data

    private void reloadData() {
        consoleLogAdapter.notifyDataSetChanged();
        updateOverflowText();
    }

    private void clearConsole() {
        console.clear();
    }

    private boolean copyConsoleOutputToClipboard() {
        return copyToClipboard(console.getText());
    }

    // Android binder transaction limit (~1 MB) makes large EXTRA_TEXT unreliable,
    // so the log is shared as a content provider attachment instead of an email body.
    private void sendConsoleOutputByEmail() {
        final Context context = getContext();
        final String packageName = context.getPackageName();
        final String subject = StringUtils.format("'%s' console log", packageName);
        final String outputText = console.getText();
        final String[] recipients = emails;

        ioQueue.dispatch(new DispatchTask("share console log") {
            @Override
            protected void execute() {
                try {
                    final Uri fileUri = writeConsoleLogFile(context, outputText);
                    dispatchQueue.dispatch(new DispatchTask("share console log ui") {
                        @Override
                        protected void execute() {
                            startShareConsoleLog(context, subject, fileUri, recipients);
                        }
                    });
                } catch (Exception e) {
                    Log.e(e, "Error while trying to send console output by email");
                    dispatchQueue.dispatch(new DispatchTask("share console log error") {
                        @Override
                        protected void execute() {
                            UIUtils.showToast(context, "Can't share log");
                        }
                    });
                }
            }
        });
    }

    private static Uri writeConsoleLogFile(Context context, String outputText) throws Exception {
        File logsDir = getLogsDir(context);
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw new Exception("Unable to create lunar console logs directory");
        }

        clearCachedLogFiles(logsDir);

        String logFileName = createLogFileName(context);
        File logFile = new File(logsDir, logFileName);
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(logFile);
            outputStream.write(outputText.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }

        return LunarConsoleFileProvider.getUriForFile(context, logFile);
    }

    static File getLogsDir(Context context) {
        return new File(context.getCacheDir(), LOG_CACHE_DIR_NAME);
    }

    private static void startShareConsoleLog(Context context, String subject, Uri fileUri, String[] recipients) {
        String displayName = fileUri.getLastPathSegment();
        if (StringUtils.IsNullOrEmpty(displayName)) {
            displayName = "console.log";
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_STREAM, fileUri);
        intent.setClipData(ClipData.newUri(context.getContentResolver(), displayName, fileUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (recipients != null && recipients.length > 0) {
            intent.putExtra(Intent.EXTRA_EMAIL, recipients);
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Share..."));
        } catch (ActivityNotFoundException ex) {
            UIUtils.showToast(context, "Can't share log");
        }
    }

    private static String createLogFileName(Context context) {
        String appName = sanitizeFileNameComponent(context.getPackageName());
        String version = sanitizeFileNameComponent(getApplicationVersionName(context));
        String buildNumber = Long.toString(getApplicationVersionCode(context));
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return StringUtils.format("%s_%s_%s_%s.log", appName, version, buildNumber, timestamp);
    }

    private static String getApplicationVersionName(Context context) {
        try {
            PackageInfo packageInfo = getPackageInfo(context);
            if (packageInfo != null && packageInfo.versionName != null && packageInfo.versionName.length() > 0) {
                return packageInfo.versionName;
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static long getApplicationVersionCode(Context context) {
        try {
            PackageInfo packageInfo = getPackageInfo(context);
            if (packageInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return packageInfo.getLongVersionCode();
                }
                return packageInfo.versionCode;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static PackageInfo getPackageInfo(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    }

    private static String sanitizeFileNameComponent(String value) {
        if (value == null) {
            return "unknown";
        }

        // Keep only filesystem-safe characters; replace everything else with underscore.
        String sanitized = value.trim().replaceAll("[^a-zA-Z0-9._-]+", "_");
        // Collapse repeated underscores from consecutive replacements.
        sanitized = sanitized.replaceAll("_+", "_");
        // Strip leading/trailing separators so the component does not start or end with . _ or -.
        sanitized = sanitized.replaceAll("^[._-]+|[._-]+$", "");
        if (sanitized.length() == 0) {
            return "unknown";
        }
        return sanitized;
    }

    private static void clearCachedLogFiles(File logsDir) {
        File[] files = logsDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }

            String name = file.getName();
            if (name.endsWith(".log") || name.endsWith(".txt")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Scrolling

    private void toggleScrollLock() {
        scrollLocked = !scrollLocked;
        scrollToBottom(console);
    }

    private void scrollToBottom(Console console) {
        if (scrollLocked) {
            int entryCount = console.getEntryCount();
            if (entryCount > 0) {
                listView.setSelection(entryCount - 1);
            }
        }
    }

    private void scrollToTop(Console console) {
        int entryCount = console.getEntryCount();
        if (entryCount > 0) {
            listView.setSelection(0);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // UI elements

    private EditText setupFilterTextEdit() {
        // TODO: make a custom class
        EditText editText = findExistingViewById(R.id.lunar_console_log_view_text_edit_filter);
        String filterText = console.entries().getFilterText();
        if (!StringUtils.IsNullOrEmpty(filterText)) {
            editText.setText(filterText);
            editText.setSelection(filterText.length());
        }

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterByText(s.toString());
            }
        });

        return editText;
    }

    private void setupLogTypeButtons() {
        setupLogTypeButton(logButton, LOG);
        setupLogTypeButton(warningButton, WARNING);
        setupLogTypeButton(errorButton, ERROR);

        updateLogButtons();
    }

    private void setupLogTypeButton(LogTypeButton button, int logType) {
        button.setOn(console.entries().isFilterLogTypeEnabled(logType));
        button.setOnStateChangeListener(this);
    }

    private void setupOperationsButtons() {
        setOnClickListener(R.id.lunar_console_button_clear, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearConsole();
            }
        });

        scrollLockButton = findExistingViewById(R.id.lunar_console_button_lock);
        final Resources resources = getContext().getResources();
        scrollLockButton.setOnDrawable(resources.getDrawable(R.drawable.lunar_console_icon_button_lock));
        scrollLockButton.setOffDrawable(resources.getDrawable(R.drawable.lunar_console_icon_button_unlock));
        scrollLockButton.setOn(scrollLocked);
        scrollLockButton.setOnStateChangeListener(new ToggleImageButton.OnStateChangeListener() {
            @Override
            public void onStateChanged(ToggleImageButton button) {
                toggleScrollLock();
            }
        });

        setOnClickListener(R.id.lunar_console_button_copy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyConsoleOutputToClipboard();
            }
        });

        setOnClickListener(R.id.lunar_console_button_email, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendConsoleOutputByEmail();
            }
        });
    }

    private void setupMoreButton() {
        setOnClickListener(R.id.lunar_console_button_more, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreOptionsMenu(v);
            }
        });
    }

    private void setupFakeStatusBar(String version) {
        String title = String.format(getResources().
                getString(R.string.lunar_console_title_fake_status_bar), version);

        TextView statusBar = findExistingViewById(R.id.lunar_console_fake_status_bar);
        statusBar.setText(title);
        statusBar.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollLockButton.setOn(false);
                scrollToTop(console);
            }
        });
    }

    private void updateLogButtons() {
        final ConsoleLogEntryList entries = console.entries();
        logButton.setCount(entries.getLogCount());
        warningButton.setCount(entries.getWarningCount());
        errorButton.setCount(entries.getErrorCount());
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // More options context menu

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void showMoreOptionsMenu(View v) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        MenuInflater inflater = popup.getMenuInflater();
        Menu menu = popup.getMenu();
        inflater.inflate(R.menu.lunar_console_more_options_menu, menu);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                final int itemId = item.getItemId();
                if (itemId == R.id.lunar_console_menu_toggle_collapse) {
                    console.setCollapsed(!console.isCollapsed());
                    return true;
                }

                if (itemId == R.id.lunar_console_menu_settings) {
                    final Activity activity = getActivity();
                    if (activity == null) {
                        Log.e(CONSOLE, "Unable to show settings activity: root activity context is lost");
                        return true;
                    }

                    try {
                        Intent intent = new Intent(activity, PluginSettingsActivity.class);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Log.e(e, "Unable to show settings activity");
                    }

                    return true;
                }

                if (itemId == R.id.lunar_console_menu_move_resize) {
                    notifyMoveResize();
                    return true;
                }

                if (itemId == R.id.lunar_console_menu_help) {
                    openHelpPage();
                    return true;
                }

                return false;
            }
        });
        MenuItem collapseItem = menu.findItem(R.id.lunar_console_menu_toggle_collapse);
        collapseItem.setChecked(console.isCollapsed());

        if (LunarConsoleConfig.isFree) {
            MenuItem menuItem = menu.add(R.string.lunar_console_more_menu_get_pro);
            menuItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    Context context = getContext();
                    return UIUtils.openURL(context, context.getString(R.string.lunar_console_url_menu_get_pro_version));
                }
            });
        }

        popup.show();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // LunarConsoleListener

    @Override
    public void onAddEntry(Console console, ConsoleLogEntry entry, boolean filtered) {
        if (filtered) {
            consoleLogAdapter.notifyDataSetChanged();
            scrollToBottom(console);
        }

        updateLogButtons();
    }

    @Override
    public void onRemoveEntries(Console console, int start, int length) {
        consoleLogAdapter.notifyDataSetChanged();
        scrollToBottom(console);
        updateLogButtons();
        updateOverflowText();
    }

    @Override
    public void onChangeEntries(Console console) {
        consoleLogAdapter.notifyDataSetChanged();
        scrollToBottom(console);
        updateLogButtons();
        updateOverflowText();
    }

    @Override
    public void onClearEntries(Console console) {
        reloadData();
        updateLogButtons();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Overflow

    private void updateOverflowText() {
        int trimmedCount = console.trimmedCount();
        if (trimmedCount > 0) {
            overflowText.setVisibility(View.VISIBLE);
            String text = getResources().getString(R.string.lunar_console_overflow_warning_text, trimmedCount);
            overflowText.setText(text);
        } else {
            overflowText.setVisibility(View.GONE);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // LULogTypeButton.OnStateChangeListener

    @Override
    public void onStateChanged(ToggleButton button) {
        int mask = 0;
        if (button == logButton) {
            mask |= getMask(LOG);
        } else if (button == warningButton) {
            mask |= getMask(WARNING);
        } else if (button == errorButton) {
            mask |= getMask(EXCEPTION) |
                    getMask(ERROR) |
                    getMask(ASSERT);
        }

        setFilterByLogTypeMask(mask, !button.isOn());
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Clipboard

    @SuppressWarnings("deprecation")
    private boolean copyToClipboard(String outputText) {
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(outputText);

            UIUtils.showToast(getContext(), "Copied to clipboard");

            return true;
        } catch (Exception e) {
            Log.e(e, "Exception while trying to copy text to clipboard");
        }

        return false;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Help

    private void openHelpPage() {
        UIUtils.openURL(getContext(), "https://goo.gl/5Z8ovV");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Listener notifications

    private void notifyMoveResize() {
        if (onMoveSizeListener != null) {
            onMoveSizeListener.onMoveResize(this);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Getters/Setters

    public Activity getActivity() {
        return activityRef.get();
    }

    public void setOnMoveSizeListener(OnMoveSizeListener onMoveSizeListener) {
        this.onMoveSizeListener = onMoveSizeListener;
    }

    public void setEmails(String[] emails) {
        this.emails = emails;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Move resize listener

    public interface OnMoveSizeListener {
        void onMoveResize(ConsoleLogView consoleLogView);
    }
}
