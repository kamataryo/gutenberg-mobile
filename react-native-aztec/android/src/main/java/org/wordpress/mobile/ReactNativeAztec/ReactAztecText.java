package org.wordpress.mobile.ReactNativeAztec;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;

import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.textinput.ContentSizeWatcher;
import com.facebook.react.views.textinput.ReactTextInputLocalData;
import com.facebook.react.views.textinput.ScrollWatcher;

import org.wordpress.aztec.AztecText;
import org.wordpress.aztec.AztecTextFormat;
import org.wordpress.aztec.ITextFormat;
import org.wordpress.aztec.plugins.IAztecPlugin;
import org.wordpress.aztec.plugins.IToolbarButton;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;

import static android.content.ClipData.*;

public class ReactAztecText extends AztecText {

    private final InputMethodManager mInputMethodManager;
    // This flag is set to true when we set the text of the EditText explicitly. In that case, no
    // *TextChanged events should be triggered. This is less expensive than removing the text
    // listeners and adding them back again after the text change is completed.
    private boolean mIsSettingTextFromJS = false;
    // This component is controlled, so we want it to get focused only when JS ask it to do so.
    // Whenever android requests focus (which it does for random reasons), it will be ignored.
    private boolean mIsJSSettingFocus = false;
    private @Nullable ArrayList<TextWatcher> mListeners;
    private @Nullable TextWatcherDelegator mTextWatcherDelegator;
    private @Nullable ContentSizeWatcher mContentSizeWatcher;
    private @Nullable ScrollWatcher mScrollWatcher;

    // FIXME: Used in `incrementAndGetEventCounter` but never read. I guess we can get rid of it, but before this
    // check when it's used in EditText in RN. (maybe tests?)
    int mNativeEventCount = 0;

    String lastSentFormattingOptionsEventString = "";
    boolean shouldHandleOnEnter = false;
    boolean shouldHandleOnBackspace = false;
    boolean shouldHandleOnPaste = false;
    boolean shouldHandleOnSelectionChange = false;
    boolean shouldHandleActiveFormatsChange = false;

    boolean shouldDeleteEnter = false;

    // This optional variable holds the outer HTML tag that will be added to the text when the user start typing in it
    // This is required to keep placeholder text working, and start typing with styled text.
    // Ref: https://github.com/wordpress-mobile/gutenberg-mobile/issues/707
    private String mTagName = "";
    private String mEmptyTagHTML = "";

    private static final HashMap<ITextFormat, String> typingFormatsMap = new HashMap<ITextFormat, String>() {
        {
            put(AztecTextFormat.FORMAT_BOLD, "bold");
            put(AztecTextFormat.FORMAT_STRONG, "bold");
            put(AztecTextFormat.FORMAT_EMPHASIS, "italic");
            put(AztecTextFormat.FORMAT_ITALIC, "italic");
            put(AztecTextFormat.FORMAT_CITE, "italic");
            put(AztecTextFormat.FORMAT_STRIKETHROUGH, "strikethrough");
        }
    };

    public ReactAztecText(ThemedReactContext reactContext) {
        super(reactContext);

        setGutenbergMode(true);

        // don't auto-focus when Aztec becomes visible.
        // Needed on rotation and multiple Aztec instances to avoid losing the exact care position.
        setFocusOnVisible(false);

        forceCaretAtStartOnTakeFocus();

        this.setAztecKeyListener(new ReactAztecText.OnAztecKeyListener() {
            @Override
            public boolean onEnterKey(Spannable text, boolean firedAfterTextChanged, int selStart, int selEnd) {
                if (shouldHandleOnEnter && !isTextChangedListenerDisabled()) {
                    return onEnter(text, firedAfterTextChanged, selStart, selEnd);
                }
                return false;
            }
            @Override
            public boolean onBackspaceKey() {
                if (shouldHandleOnBackspace && !isTextChangedListenerDisabled()) {
                    String content = toHtml(getText(), false);
                    if (TextUtils.isEmpty(content)) {
                        return onBackspace();
                    }
                    else {
                        if (!content.equals(mEmptyTagHTML)) {
                            return onBackspace();
                        }
                    }
                }
                return false;
            }
        });

        mInputMethodManager = (InputMethodManager)
                Assertions.assertNotNull(getContext().getSystemService(Context.INPUT_METHOD_SERVICE));
        this.setOnSelectionChangedListener(new OnSelectionChangedListener() {
            @Override
            public void onSelectionChanged(int selStart, int selEnd) {
                ReactAztecText.this.updateToolbarButtons(selStart, selEnd);
                ReactAztecText.this.propagateSelectionChanges(selStart, selEnd);
            }
        });
        this.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    private void forceCaretAtStartOnTakeFocus() {
        // set a custom ArrowKeyMovementMethod: sets caret to the start of the text instead of the default (end of text)
        // Fixes https://github.com/wordpress-mobile/gutenberg-mobile/issues/602
        // onTakeFocus adapted from the Android source code at:
        //  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/core/java/android/text/method/ArrowKeyMovementMethod.java#316
        setMovementMethod(new ReactAztecArrowKeyMovementMethod());
    }

    @Override
    public void refreshText() {
        super.refreshText();
        onContentSizeChange();
    }

    void addPlugin(IAztecPlugin plugin) {
        super.getPlugins().add(plugin);
        if (plugin instanceof IToolbarButton && getToolbar() != null ) {
            getToolbar().addButton((IToolbarButton)plugin);
        }
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (shouldHandleOnPaste) {
            switch (id) {
                case android.R.id.paste:
                    return onPaste(false);
                case android.R.id.pasteAsPlainText:
                    return onPaste(true);
            }
        }

        return super.onTextContextMenuItem(id);
    }

    // VisibleForTesting from {@link TextInputEventsTestCase}.
    public void requestFocusFromJS() {
        mIsJSSettingFocus = true;
        requestFocus();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // let's pinpoint the caret line to ask the system to bring that line into the viewport
                int lineNumber = getLayout().getLineForOffset(getSelectionStart());

                Rect caretLineRect = new Rect();
                getLineBounds(lineNumber, caretLineRect);
                requestRectangleOnScreen(caretLineRect);
            }
        }, 100);
        mIsJSSettingFocus = false;
    }

    void clearFocusFromJS() {
        clearFocus();
    }

    @Override
    public void clearFocus() {
        setFocusableInTouchMode(false);
        setFocusable(false);
        super.clearFocus();
        hideSoftKeyboard();
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        // Always return true if we are already focused. This is used by android in certain places,
        // such as text selection.
        if (isFocused()) {
            return true;
        }
        //TODO check why it's needed - doesn't seem to work fine with this in it, since each focus call
        // from the Android FW is skipped here.
        /*if (!mIsJSSettingFocus) {
            return false;
        }*/
        setFocusableInTouchMode(true);
        setFocusable(true);
        boolean focused = super.requestFocus(direction, previouslyFocusedRect);
        showSoftKeyboard();
        return focused;
    }

    private void showSoftKeyboard() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (mInputMethodManager != null) {
                    mInputMethodManager.showSoftInput(ReactAztecText.this, 0);
                }
            }
        });
    }

    private void hideSoftKeyboard() {
        mInputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    public void setScrollWatcher(ScrollWatcher scrollWatcher) {
        mScrollWatcher = scrollWatcher;
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);

        if (mScrollWatcher != null) {
            mScrollWatcher.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        }
    }

    public void setContentSizeWatcher(ContentSizeWatcher contentSizeWatcher) {
        mContentSizeWatcher = contentSizeWatcher;
    }

    private void onContentSizeChange() {
        if (mContentSizeWatcher != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (mContentSizeWatcher != null) {
                        mContentSizeWatcher.onLayout();
                    }
                }
            });
        }
        setIntrinsicContentSize();
    }

    public void setTagName(@Nullable String tagName) {
        mTagName = tagName;
        mEmptyTagHTML = "<" + mTagName + "></" + mTagName + ">";
    }

    public String getTagName() {
        return mTagName;
    }


    private void updateToolbarButtons(int selStart, int selEnd) {
        ArrayList<ITextFormat> appliedStyles = getAppliedStyles(selStart, selEnd);
        updateToolbarButtons(appliedStyles);
    }

    private void updateToolbarButtons(ArrayList<ITextFormat> appliedStyles) {
        // Read the applied styles and get the String list of formatting options
        LinkedList<String> formattingOptions = new LinkedList<>();
        for (ITextFormat currentStyle : appliedStyles) {
            if ((currentStyle == AztecTextFormat.FORMAT_STRONG || currentStyle == AztecTextFormat.FORMAT_BOLD)
                    && !formattingOptions.contains("bold")) {
                formattingOptions.add("bold");
            }
            if ((currentStyle == AztecTextFormat.FORMAT_ITALIC || currentStyle == AztecTextFormat.FORMAT_CITE)
                    && !formattingOptions.contains("italic")) {
                formattingOptions.add("italic");
            }
            if (currentStyle == AztecTextFormat.FORMAT_STRIKETHROUGH) {
                formattingOptions.add("strikethrough");
            }
        }

        // Check if the same formatting event was already sent
        String newOptionsAsString = "";
        for (String currentFormatting: formattingOptions) {
            newOptionsAsString += currentFormatting;
        }
        if (newOptionsAsString.equals(lastSentFormattingOptionsEventString)) {
            // no need to send any event now
            return;
        }
        lastSentFormattingOptionsEventString = newOptionsAsString;

        if (shouldHandleActiveFormatsChange) {
            ReactContext reactContext = (ReactContext) getContext();
            EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
            eventDispatcher.dispatchEvent(
                    new ReactAztecFormattingChangeEvent(
                            getId(),
                            formattingOptions.toArray(new String[formattingOptions.size()])
                    )
            );
        }
    }

    private void propagateSelectionChanges(int selStart, int selEnd) {
        if (!shouldHandleOnSelectionChange) {
            return;
        }
        String content = toHtml(getText(), false);
        ReactContext reactContext = (ReactContext) getContext();
        EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        eventDispatcher.dispatchEvent(
                new ReactAztecSelectionChangeEvent(getId(), content, selStart, selEnd, incrementAndGetEventCounter())
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        onContentSizeChange();
    }

    private void setIntrinsicContentSize() {
        ReactContext reactContext = (ReactContext) getContext();
        UIManagerModule uiManager = reactContext.getNativeModule(UIManagerModule.class);
        final ReactTextInputLocalData localData = new ReactTextInputLocalData(this);
        uiManager.setViewLocalData(getId(), localData);
    }

    //// Text changed events

    public int incrementAndGetEventCounter() {
        return ++mNativeEventCount;
    }

    @Override
    public void addTextChangedListener(TextWatcher watcher) {
        if (mListeners == null) {
            mListeners = new ArrayList<>();
            super.addTextChangedListener(getTextWatcherDelegator());

            // Keep the enter pressed watcher at the beginning of the watchers list.
            // We want to intercept Enter.key as soon as possible, and before other listeners start modifying the text.
            // Also note that this Watchers, when the AztecKeyListener is set, keep hold a copy of the content in the editor.
            mListeners.add(new EnterPressedWatcher(this, new EnterDeleter() {
                @Override
                public boolean shouldDeleteEnter() {
                    return shouldDeleteEnter;
                }
            }));
        }

        mListeners.add(watcher);
    }

    @Override
    public void removeTextChangedListener(TextWatcher watcher) {
        if (mListeners != null) {
            mListeners.remove(watcher);

            if (mListeners.isEmpty()) {
                mListeners = null;
                super.removeTextChangedListener(getTextWatcherDelegator());
            }
        }
    }

    private TextWatcherDelegator getTextWatcherDelegator() {
        if (mTextWatcherDelegator == null) {
            mTextWatcherDelegator = new TextWatcherDelegator();
        }
        return mTextWatcherDelegator;
    }

    public void setIsSettingTextFromJS(boolean mIsSettingTextFromJS) {
        this.mIsSettingTextFromJS = mIsSettingTextFromJS;
    }

    private boolean onEnter(Spannable text, boolean firedAfterTextChanged, int selStart, int selEnd) {
        disableTextChangedListener();
        String content = toHtml(text, false);
        int cursorPositionStart = firedAfterTextChanged ? selStart : getSelectionStart();
        int cursorPositionEnd = firedAfterTextChanged ? selEnd : getSelectionEnd();
        enableTextChangedListener();
        ReactContext reactContext = (ReactContext) getContext();
        EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        eventDispatcher.dispatchEvent(
                new ReactAztecEnterEvent(getId(), content, cursorPositionStart, cursorPositionEnd,
                        firedAfterTextChanged, incrementAndGetEventCounter())
        );
        return true;
    }

    private boolean onBackspace() {
        int cursorPositionStart = getSelectionStart();
        int cursorPositionEnd = getSelectionEnd();
        // Make sure to report backspace at the beginning only, with no selection.
        if (cursorPositionStart != 0 || cursorPositionEnd != 0) {
            return false;
        }

        disableTextChangedListener();
        String content = toHtml(getText(), false);
        enableTextChangedListener();
        ReactContext reactContext = (ReactContext) getContext();
        EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        // TODO: isRTL? Should be passed here?
        eventDispatcher.dispatchEvent(
                new ReactAztecBackspaceEvent(getId(), content, cursorPositionStart, cursorPositionEnd)
        );
        return true;
    }

    /**
     * Handle paste action by retrieving clipboard contents and dispatching a
     * {@link ReactAztecPasteEvent} with the data
     *
     * @param   isPastedAsPlainText boolean indicating whether the paste action chosen was
     *                         "PASTE AS PLAIN TEXT"
     *
     * @return  boolean to indicate that the action was handled (always true)
     */
    private boolean onPaste(boolean isPastedAsPlainText) {
        ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(
                Context.CLIPBOARD_SERVICE);

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();

        if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
            ClipData clipData = clipboardManager.getPrimaryClip();
            int itemCount = clipData.getItemCount();

            for (int i = 0; i < itemCount; i++) {
                Item item = clipData.getItemAt(i);
                text.append(item.coerceToText(getContext()));
                if (!isPastedAsPlainText) {
                    html.append(item.coerceToHtmlText(getContext()));
                }
            }
        }

        // temporarily disable listener during call to toHtml()
        disableTextChangedListener();
        String content = toHtml(getText(), false);
        int cursorPositionStart = getSelectionStart();
        int cursorPositionEnd = getSelectionEnd();
        enableTextChangedListener();
        ReactContext reactContext = (ReactContext) getContext();
        EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class)
                .getEventDispatcher();
        eventDispatcher.dispatchEvent(new ReactAztecPasteEvent(getId(), content,
                cursorPositionStart, cursorPositionEnd, text.toString(), html.toString())
        );
        return true;
    }

    public void setActiveFormats(Iterable<String> newFormats) {
        Set<ITextFormat> selectedStylesSet = new HashSet<>(getSelectedStyles());
        Set<ITextFormat> newFormatsSet = new HashSet<>();
        for (String newFormat : newFormats) {
            switch (newFormat) {
                case "bold":
                    newFormatsSet.add(AztecTextFormat.FORMAT_STRONG);
                    break;
                case "italic":
                    newFormatsSet.add(AztecTextFormat.FORMAT_EMPHASIS);
                    break;
                case "strikethrough":
                    newFormatsSet.add(AztecTextFormat.FORMAT_STRIKETHROUGH);
                    break;
            }
        }
        selectedStylesSet.removeAll(typingFormatsMap.keySet());
        selectedStylesSet.addAll(newFormatsSet);
        ArrayList<ITextFormat> newStylesList = new ArrayList<>(selectedStylesSet);
        setSelectedStyles(newStylesList);
        updateToolbarButtons(newStylesList);
    }

    protected boolean isEnterPressedUnderway() {
        return EnterPressedWatcher.Companion.isEnterPressedUnderway(getText());
    }

    /**
     * This class will redirect *TextChanged calls to the listeners only in the case where the text
     * is changed by the user, and not explicitly set by JS.
     */
    private class TextWatcherDelegator implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (!mIsSettingTextFromJS && mListeners != null) {
                for (TextWatcher listener : mListeners) {
                    listener.beforeTextChanged(s, start, count, after);
                }
            }
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!mIsSettingTextFromJS && mListeners != null) {
                for (TextWatcher listener : mListeners) {
                    listener.onTextChanged(s, start, before, count);
                }
            }

            onContentSizeChange();
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (!mIsSettingTextFromJS && mListeners != null) {
                for (TextWatcher listener : mListeners) {
                    listener.afterTextChanged(s);
                }
            }
        }
    }
}
