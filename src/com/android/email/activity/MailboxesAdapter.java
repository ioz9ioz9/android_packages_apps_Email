/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.ResourceHelper;
import com.android.email.Utility;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MergeCursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The adapter for displaying mailboxes.
 *
 * Do not use {@link #getItemId(int)} -- It's only for ListView.  Use {@link #getMailboxId}
 * instead.  (See the comment below)
 *
 * TODO Show "Starred" per account
 * TODO Unit test, when UI is settled.
 */
/* package */ class MailboxesAdapter extends CursorAdapter {
    public static final String TAG = "MailboxesAdapter";

    public static final int MODE_NORMAL = 0;
    public static final int MODE_MOVE_TO_TARGET = 1;

    /**
     * Row type, used in the "row_type" in {@link PROJECTION}.
     * {@link #ROW_TYPE_MAILBOX} for regular mailboxes and combined mailboxes.
     * {@link #ROW_TYPE_ACCOUNT} for account row in the combined view.
     */
    private static final int ROW_TYPE_MAILBOX = 0;
    private static final int ROW_TYPE_ACCOUNT = 1;

    /**
     * Return value from {@link #getCountType}.
     */
    public static final int COUNT_TYPE_UNREAD = 0;
    public static final int COUNT_TYPE_TOTAL = 1;
    public static final int COUNT_TYPE_NO_COUNT = 2;

    /*
     * Note here we have two ID columns.  The first one is for ListView, which doesn't like ID
     * values to be negative.  The second one is the actual mailbox ID, which we use in the rest
     * of code.
     * ListView uses row IDs for some operations, including onSave/RestoreInstanceState,
     * and if we use negative IDs they don't work as expected.
     * Because ListView finds the ID column by name ("_id"), we rename the second column
     * so that ListView gets the correct column.
     */
    /* package */ static final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.ID + " AS org_mailbox_id",
            MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
            MailboxColumns.MESSAGE_COUNT, ROW_TYPE_MAILBOX + " AS row_type"};
    // Column 0 is only for ListView; we don't use it in our code.
    private static final int COLUMN_ID = 1;
    private static final int COLUMN_DISPLAY_NAME = 2;
    private static final int COLUMN_TYPE = 3;
    private static final int COLUMN_UNREAD_COUNT = 4;
    private static final int COLUMN_MESSAGE_COUNT = 5;
    private static final int COLUMN_ROW_TYPE = 6;

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + Mailbox.USER_VISIBLE_MAILBOX_SELECTION;

    private static final String MAILBOX_SELECTION_MOVE_TO_FOLDER =
            MAILBOX_SELECTION + " AND " + Mailbox.MOVE_TO_TARGET_MAILBOX_SELECTION;

    private static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE +
            " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0" +
            " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN 1" +
            " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN 2" +
            " WHEN " + Mailbox.TYPE_SENT    + " THEN 3" +
            " WHEN " + Mailbox.TYPE_TRASH   + " THEN 4" +
            " WHEN " + Mailbox.TYPE_JUNK    + " THEN 5" +
            // Other mailboxes (i.e. of Mailbox.TYPE_MAIL) are shown in alphabetical order.
            " ELSE 10 END" +
            " ," + MailboxColumns.DISPLAY_NAME;

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final ResourceHelper mResourceHelper;

    private final int mMode;
    private static boolean sEnableUpdate = true;
    private Callback mCallback;

    public MailboxesAdapter(Context context, int mode, Callback callback) {
        super(context, null, 0 /* no auto-requery */);
        mContext = context;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mMode = mode;
        mCallback = callback;
        mResourceHelper = ResourceHelper.getInstance(mContext);
    }

    /**
     * Callback interface used to report clicks other than the basic list item click or longpress.
     */
    public interface Callback {
        /**
         * Callback for setting background of mailbox list items during a drag
         */
        public void onSetDropTargetBackground(MailboxListItem listItem);
    }

    public static class EmptyCallback implements Callback {
        @Override
        public void onSetDropTargetBackground(MailboxListItem listItem) {};
    }

    /**
     * @return true if the current row is of an account in the combined view.
     */
    private static boolean isAccountRow(Cursor c) {
        return c.getInt(COLUMN_ROW_TYPE) == ROW_TYPE_ACCOUNT;
    }

    /**
     * @return true if the specified row is of an account in the combined view.
     */
    public boolean isAccountRow(int position) {
        return isAccountRow((Cursor) getItem(position));
    }

    /**
     * @return which type of count should be used for the current row.
     * Possible return values are COUNT_TYPE_*.
     */
    private static int getCountTypeForMailboxType(Cursor c) {
        if (isAccountRow(c)) {
            return COUNT_TYPE_UNREAD; // Use the unread count for account rows.
        }
        switch (c.getInt(COLUMN_TYPE)) {
            case Mailbox.TYPE_DRAFTS:
            case Mailbox.TYPE_OUTBOX:
                return COUNT_TYPE_TOTAL;
            case Mailbox.TYPE_SENT:
            case Mailbox.TYPE_TRASH:
                return COUNT_TYPE_NO_COUNT;
        }
        return COUNT_TYPE_UNREAD;
    }

    /**
     * @return count type for the specified row.  See {@link #getCountTypeForMailboxType}
     */
    private int getCountType(int position) {
        if (isAccountRow(position)) {
            return COUNT_TYPE_UNREAD;
        }
        return getCountTypeForMailboxType((Cursor) getItem(position));
    }

    /**
     * @return count type for the specified row.
     */
    public int getUnreadCount(int position) {
        if (getCountType(position) != COUNT_TYPE_UNREAD) {
            return 0; // Don't have a unread count.
        }
        Cursor c = (Cursor) getItem(position);
        return c.getInt(COLUMN_UNREAD_COUNT);
    }

    /**
     * @return display name for the specified row.
     */
    public String getDisplayName(int position) {
        Cursor c = (Cursor) getItem(position);
        return getDisplayName(mContext, c);
    }

    /**
     * @return ID of the mailbox (or account, if {@link #isAccountRow} == true) of the specified
     * row.
     */
    public long getId(int position) {
        Cursor c = (Cursor) getItem(position);
        return c.getLong(COLUMN_ID);
    }

    /**
     * Turn on and off list updates; during a drag operation, we do NOT want to the list of
     * mailboxes to update, as this would be visually jarring
     * @param state whether or not the MailboxList can be updated
     */
    public void enableUpdates(boolean state) {
        sEnableUpdate = state;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        switch (mMode) {
            case MODE_NORMAL:
                bindViewNormalMode(view, context, cursor);
                return;
            case MODE_MOVE_TO_TARGET:
                bindViewMoveToTargetMode(view, context, cursor);
                return;
        }
        throw new IllegalStateException();
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        switch (mMode) {
            case MODE_NORMAL:
                return newViewNormalMode(context, cursor, parent);
            case MODE_MOVE_TO_TARGET:
                return newViewMoveToTargetMode(context, cursor, parent);
        }
        throw new IllegalStateException();
    }

    private static String getDisplayName(Context context, Cursor cursor) {
        String name = null;
        if (cursor.getInt(COLUMN_ROW_TYPE) == ROW_TYPE_MAILBOX) {
            // If it's a mailbox (as opposed to account row in combined view), and of certain types,
            // we use the predefined names.
            final int type = cursor.getInt(COLUMN_TYPE);
            final long mailboxId = cursor.getLong(COLUMN_ID);
            name = Utility.FolderProperties.getInstance(context)
                    .getDisplayName(type, mailboxId);
        }
        if (name == null) {
            name = cursor.getString(COLUMN_DISPLAY_NAME);
        }
        return name;
    }

    private void bindViewNormalMode(View view, Context context, Cursor cursor) {
        final boolean isAccount = isAccountRow(cursor);
        final int type = cursor.getInt(COLUMN_TYPE);
        final long id = cursor.getLong(COLUMN_ID);

        MailboxListItem listItem = (MailboxListItem)view;
        listItem.mMailboxType = type;
        listItem.mMailboxId = id;
        listItem.mAdapter = this;

        // Set the background depending on whether we're in drag mode, the mailbox is a valid
        // target, etc.
        mCallback.onSetDropTargetBackground(listItem);

        // Set mailbox name
        final TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
        nameView.setText(getDisplayName(context, cursor));

        // Set count
        final int count;
        switch (getCountTypeForMailboxType(cursor)) {
            case COUNT_TYPE_UNREAD:
                count = cursor.getInt(COLUMN_UNREAD_COUNT);
                break;
            case COUNT_TYPE_TOTAL:
                count = cursor.getInt(COLUMN_MESSAGE_COUNT);
                break;
            default: // no count
                count = 0;
                break;
        }
        final TextView countView = (TextView) view.findViewById(R.id.message_count);

        // If the unread count is zero, not to show countView.
        if (count > 0) {
            countView.setVisibility(View.VISIBLE);
            countView.setText(Integer.toString(count));
        } else {
            countView.setVisibility(View.GONE);
        }

        // Set folder icon
        ((ImageView) view.findViewById(R.id.folder_icon)).setImageDrawable(
                Utility.FolderProperties.getInstance(context).getIcon(type, id));

        final View chipView = view.findViewById(R.id.color_chip);
        if (isAccount) {
            chipView.setVisibility(View.VISIBLE);
            chipView.setBackgroundColor(mResourceHelper.getAccountColor(id));
        } else {
            chipView.setVisibility(View.GONE);
        }
    }

    private View newViewNormalMode(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }

    private void bindViewMoveToTargetMode(View view, Context context, Cursor cursor) {
        TextView t = (TextView) view;
        t.setText(getDisplayName(context, cursor));
    }

    private View newViewMoveToTargetMode(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
    }

    /**
     * @return mailboxes Loader for an account.
     */
    public static Loader<Cursor> createLoader(Context context, long accountId,
            int mode) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxesAdapter createLoader accountId=" + accountId);
        }
        if (accountId != Account.ACCOUNT_ID_COMBINED_VIEW) {
            return new MailboxesLoader(context, accountId, mode);
        } else {
            return new CombinedMailboxesLoader(context);
        }
    }

    /**
     * Loader for mailboxes of an account.
     */
    private static class MailboxesLoader extends ThrottlingCursorLoader {
        private final Context mContext;
        private final int mMode;
        private final long mAccountId;

        private static String getSelection(int mode) {
            if (mode == MODE_MOVE_TO_TARGET) {
                return MAILBOX_SELECTION_MOVE_TO_FOLDER;
            } else {
                return MAILBOX_SELECTION;
            }
        }

        public MailboxesLoader(Context context, long accountId, int mode) {
            super(context, EmailContent.Mailbox.CONTENT_URI,
                    MailboxesAdapter.PROJECTION, getSelection(mode),
                    new String[] { String.valueOf(accountId) }, MAILBOX_ORDER_BY);
            mContext = context;
            mMode = mode;
            mAccountId = accountId;
        }

        @Override
        public void onContentChanged() {
            if (sEnableUpdate) {
                super.onContentChanged();
            }
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor mailboxesCursor = super.loadInBackground();
            if (mMode == MODE_MOVE_TO_TARGET) {
                return Utility.CloseTraceCursorWrapper.get(mailboxesCursor);
            }

            // Add "Starred", only if the account has at least one starred message.
            // TODO It's currently "combined starred", but the plan is to make it per-account
            // starred.
            final int accountStarredCount = Message.getFavoriteMessageCount(mContext, mAccountId);
            if (accountStarredCount == 0) {
                return Utility.CloseTraceCursorWrapper.get(mailboxesCursor); // no starred message
            }

            final MatrixCursor starredCursor = new MatrixCursor(getProjection());

            final int totalStarredCount = Message.getFavoriteMessageCount(mContext);
            addSummaryMailboxRow(mContext, starredCursor,
                    Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL, totalStarredCount, true);

            return Utility.CloseTraceCursorWrapper.get(
                    new MergeCursor(new Cursor[] {starredCursor, mailboxesCursor}));
        }
    }

    /**
     * Loader for mailboxes in "Combined view".
     */
    private static class CombinedMailboxesLoader extends ThrottlingCursorLoader {
        private static final String[] ACCOUNT_PROJECTION = new String[] {
                EmailContent.RECORD_ID, AccountColumns.DISPLAY_NAME,
                };
        private static final int COLUMN_ACCOUND_ID = 0;
        private static final int COLUMN_ACCOUNT_DISPLAY_NAME = 1;

        private final Context mContext;

        public CombinedMailboxesLoader(Context context) {
            // Tell the super class to load accounts.
            // But we don't directly return that...
            super(context, Account.CONTENT_URI, ACCOUNT_PROJECTION, null, null, null);
            mContext = context;
        }

        @Override
        public Cursor loadInBackground() {
            final MatrixCursor combinedWithAccounts = getSpecialMailboxesCursor(mContext);
            final Cursor accounts = super.loadInBackground();
            try {
                accounts.moveToPosition(-1);
                while (accounts.moveToNext()) {
                    RowBuilder row =  combinedWithAccounts.newRow();
                    final long accountId = accounts.getLong(COLUMN_ACCOUND_ID);
                    row.add(accountId);
                    row.add(accountId);
                    row.add(accounts.getString(COLUMN_ACCOUNT_DISPLAY_NAME));
                    row.add(-1); // No mailbox type.  Shouldn't really be used.
                    final int unreadCount = Mailbox.getUnreadCountByAccountAndMailboxType(
                            mContext, accountId, Mailbox.TYPE_INBOX);
                    row.add(unreadCount);
                    row.add(unreadCount);
                    row.add(ROW_TYPE_ACCOUNT);
                }
            } finally {
                accounts.close();
            }

            return Utility.CloseTraceCursorWrapper.get(combinedWithAccounts);
        }
    }

    /* package */ static MatrixCursor getSpecialMailboxesCursor(Context context) {
        MatrixCursor cursor = new MatrixCursor(PROJECTION);
        // Combined inbox -- show unread count
        addSummaryMailboxRow(context, cursor,
                Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX,
                Mailbox.getUnreadCountByMailboxType(context, Mailbox.TYPE_INBOX), true);

        // Favorite (starred) -- show # of favorites
        addSummaryMailboxRow(context, cursor,
                Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL,
                Message.getFavoriteMessageCount(context), false);

        // Drafts -- show # of drafts
        addSummaryMailboxRow(context, cursor,
                Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS,
                Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_DRAFTS), false);

        // Outbox -- # of outstanding messages
        addSummaryMailboxRow(context, cursor,
                Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX,
                Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_OUTBOX), false);

        return cursor;
    }

    private static void addSummaryMailboxRow(Context context, MatrixCursor cursor,
            long id, int type, int count, boolean showAlways) {
        if (id >= 0) {
            throw new IllegalArgumentException(); // Must be QUERY_ALL_*, which are all negative.
        }
        if (showAlways || (count > 0)) {
            RowBuilder row = cursor.newRow();
            row.add(Long.MAX_VALUE + id); // Map QUERY_ALL_* constants to positive ints.
            row.add(id); // The real mailbox ID.
            row.add(""); // Display name.  We get it from FolderProperties.
            row.add(type);
            row.add(count);
            row.add(count);
            row.add(ROW_TYPE_MAILBOX);
        }
    }

    /* package */ static long getIdForTest(Cursor cursor) {
        return cursor.getLong(COLUMN_ID);
    }

    /* package */ static int getTypeForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_TYPE);
    }

    /* package */ static int getMessageCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_MESSAGE_COUNT);
    }

    /* package */ static int getUnreadCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_UNREAD_COUNT);
    }

    // STOPSHIP delete this
    @Override
    public long getItemId(int position) {
        try {
            return super.getItemId(position);
        } catch (RuntimeException re) {
            final Cursor c = getCursor();
            android.util.Log.w(Email.LOG_TAG, "Crash in getItemId, this=" + this
                    + "  cursor=" + Utility.dumpCursor(c), re);
            Utility.CloseTraceCursorWrapper.log(c);
            throw re;
        }
    }
}