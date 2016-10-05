package org.wordpress.android.ui.media;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.Toast;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.util.ListUtils;
import org.wordpress.android.util.ToastUtils;
import org.xmlrpc.android.ApiHelper;

import java.util.ArrayList;

import javax.inject.Inject;

/**
 * An activity where the user can add new images to their media gallery or where the user
 * can choose a single image to embed into their post.
 */
public class MediaGalleryPickerActivity extends AppCompatActivity
        implements MultiChoiceModeListener, ActionMode.Callback, MediaGridAdapter.MediaGridAdapterCallback,
                   AdapterView.OnItemClickListener {
    @Inject MediaStore mMediaStore;

    private GridView mGridView;
    private MediaGridAdapter mGridAdapter;
    private ActionMode mActionMode;

    private ArrayList<Long> mFilteredItems;
    private boolean mIsSelectOneItem;
    private boolean mIsRefreshing;
    private boolean mHasRetrievedAllMedia;

    private static final String STATE_FILTERED_ITEMS = "STATE_FILTERED_ITEMS";
    private static final String STATE_SELECTED_ITEMS = "STATE_SELECTED_ITEMS";
    private static final String STATE_IS_SELECT_ONE_ITEM = "STATE_IS_SELECT_ONE_ITEM";

    public static final int REQUEST_CODE = 4000;
    public static final String PARAM_SELECT_ONE_ITEM = "PARAM_SELECT_ONE_ITEM";
    public static final String PARAM_SELECTED_IDS = "PARAM_SELECTED_IDS";
    public static final String RESULT_IDS = "RESULT_IDS";
    public static final String TAG = MediaGalleryPickerActivity.class.getSimpleName();

    private int mOldMediaSyncOffset = 0;
    private SiteModel mSite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getApplication()).component().inject(this);

        ArrayList<Long> selectedItems = new ArrayList<>();
        mIsSelectOneItem = getIntent().getBooleanExtra(PARAM_SELECT_ONE_ITEM, false);

        ArrayList<Long> prevSelectedItems = ListUtils.fromLongArray(getIntent().getLongArrayExtra(PARAM_SELECTED_IDS));
        if (prevSelectedItems != null) {
            selectedItems.addAll(prevSelectedItems);
        }

        if (savedInstanceState != null) {
            ArrayList<Long> list = ListUtils.fromLongArray(savedInstanceState.getLongArray(STATE_SELECTED_ITEMS));
            selectedItems.addAll(list);
            mFilteredItems = ListUtils.fromLongArray(savedInstanceState.getLongArray(STATE_FILTERED_ITEMS));
            mIsSelectOneItem = savedInstanceState.getBoolean(STATE_IS_SELECT_ONE_ITEM, mIsSelectOneItem);
        }


        if (savedInstanceState == null) {
            mSite = (SiteModel) getIntent().getSerializableExtra(WordPress.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }

        if (mSite == null) {
            ToastUtils.showToast(this, R.string.blog_not_found, ToastUtils.Duration.SHORT);
            finish();
            return;
        }

        setContentView(R.layout.media_gallery_picker_layout);
        mGridView = (GridView) findViewById(R.id.media_gallery_picker_gridview);
        mGridView.setMultiChoiceModeListener(this);
        mGridView.setOnItemClickListener(this);
        // TODO: We want to inject the image loader in this class instead of using a static field.
        mGridAdapter = new MediaGridAdapter(this, mSite, null, 0, WordPress.imageLoader);
        mGridAdapter.setSelectedItems(selectedItems);
        mGridAdapter.setCallback(this);
        mGridView.setAdapter(mGridAdapter);
        if (mIsSelectOneItem) {
            setTitle(R.string.select_from_media_library);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        } else {
            mActionMode = startActionMode(this);
            mActionMode.setTitle(String.format(getString(R.string.cab_selected),
                    mGridAdapter.getSelectedItems().size()));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshViews();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLongArray(STATE_SELECTED_ITEMS, ListUtils.toLongArray(mGridAdapter.getSelectedItems()));
        outState.putLongArray(STATE_FILTERED_ITEMS, ListUtils.toLongArray(mFilteredItems));
        outState.putBoolean(STATE_IS_SELECT_ONE_ITEM, mIsSelectOneItem);
    }

    private void refreshViews() {
        Cursor cursor = mMediaStore.getSiteImagesExcludingIdsAsCursor(mSite, mFilteredItems);
        if (cursor.getCount() == 0) {
            refreshMediaFromServer(0);
        } else {
            mGridAdapter.swapCursor(cursor);
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            setResult(RESULT_CANCELED, new Intent());
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mIsSelectOneItem) {
            // Single select, just finish the activity once an item is selected
            mGridAdapter.setItemSelected(position, true);
            Intent intent = new Intent();
            intent.putExtra(RESULT_IDS, ListUtils.toLongArray(mGridAdapter.getSelectedItems()));
            setResult(RESULT_OK, intent);
            finish();
        } else {
            mGridAdapter.toggleItemSelected(position);
            mActionMode.setTitle(String.format(getString(R.string.cab_selected),
                    mGridAdapter.getSelectedItems().size()));
        }
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        mGridAdapter.setItemSelected(position, checked);
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        Intent intent = new Intent();
        intent.putExtra(RESULT_IDS, ListUtils.toLongArray(mGridAdapter.getSelectedItems()));
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void fetchMoreData(int offset) {
        if (!mHasRetrievedAllMedia) {
            refreshMediaFromServer(offset);
        }
    }

    @Override
    public void onRetryUpload(long mediaId) {
    }

    @Override
    public boolean isInMultiSelect() {
        return false;
    }

    private void noMediaFinish() {
        ToastUtils.showToast(this, R.string.media_empty_list, ToastUtils.Duration.LONG);
        // Delay activity finish
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    void refreshMediaFromServer(int offset) {
        if (offset == 0 || !mIsRefreshing) {
            if (offset == mOldMediaSyncOffset) {
                // we're pulling the same data again for some reason. Pull from the beginning.
                offset = 0;
            }
            mOldMediaSyncOffset = offset;
            mIsRefreshing = true;
            mGridAdapter.setRefreshing(true);

            ApiHelper.SyncMediaLibraryTask.Callback callback = new ApiHelper.SyncMediaLibraryTask.Callback() {
                // refersh db from server. If returned count is 0, we've retrieved all the media.
                // stop retrieving until the user manually refreshes

                @Override
                public void onSuccess(int count) {
                    MediaGridAdapter adapter = (MediaGridAdapter) mGridView.getAdapter();
                    mHasRetrievedAllMedia = (count == 0);
                    adapter.setHasRetrievedAll(mHasRetrievedAllMedia);
                    String blogId = String.valueOf(mSite.getId());
                    if (WordPress.wpDB.getMediaCountAll(blogId) == 0 && count == 0) {
                        // There is no media at all
                        noMediaFinish();
                    }
                    mIsRefreshing = false;

                    // the activity may be gone by the time this finishes, so check for it
                    if (!isFinishing()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                //mListener.onMediaItemListDownloaded();
                                mGridAdapter.setRefreshing(false);
                                Cursor cursor = mMediaStore.getSiteImagesExcludingIdsAsCursor(mSite, mFilteredItems);
                                mGridAdapter.swapCursor(cursor);

                            }
                        });
                    }
                }

                @Override
                public void onFailure(ApiHelper.ErrorType errorType, String errorMessage, Throwable throwable) {
                    if (errorType != ApiHelper.ErrorType.NO_ERROR) {
                        String message = errorType == ApiHelper.ErrorType.NO_UPLOAD_FILES_CAP
                                ? getString(R.string.media_error_no_permission)
                                : getString(R.string.error_refresh_media);
                        Toast.makeText(MediaGalleryPickerActivity.this, message, Toast.LENGTH_SHORT).show();
                        MediaGridAdapter adapter = (MediaGridAdapter) mGridView.getAdapter();
                        mHasRetrievedAllMedia = true;
                        adapter.setHasRetrievedAll(mHasRetrievedAllMedia);
                    }

                    // the activity may be cone by the time we get this, so check for it
                    if (!isFinishing()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mIsRefreshing = false;
                                mGridAdapter.setRefreshing(false);
                            }
                        });
                    }

                }
            };
            ApiHelper.SyncMediaLibraryTask getMediaTask = new ApiHelper.SyncMediaLibraryTask(offset,
                    MediaGridFragment.Filter.ALL, callback, mSite);
            getMediaTask.execute();
        }
    }
}
