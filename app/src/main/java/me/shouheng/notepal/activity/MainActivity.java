package me.shouheng.notepal.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;

import com.afollestad.materialdialogs.color.ColorChooserDialog;
import com.github.clans.fab.FloatingActionButton;

import org.polaric.colorful.PermissionUtils;

import java.util.Arrays;
import java.util.List;

import me.shouheng.notepal.PalmApp;
import me.shouheng.notepal.R;
import me.shouheng.notepal.config.Constants;
import me.shouheng.notepal.databinding.ActivityMainBinding;
import me.shouheng.notepal.databinding.ActivityMainNavHeaderBinding;
import me.shouheng.notepal.dialog.AttachmentPickerDialog;
import me.shouheng.notepal.dialog.MindSnaggingDialog;
import me.shouheng.notepal.dialog.NotebookEditDialog;
import me.shouheng.notepal.dialog.NoticeDialog;
import me.shouheng.notepal.fragment.NotesFragment;
import me.shouheng.notepal.fragment.SnaggingsFragment;
import me.shouheng.notepal.fragment.SnaggingsFragment.OnSnagginsInteractListener;
import me.shouheng.notepal.intro.IntroActivity;
import me.shouheng.notepal.listener.OnAttachingFileListener;
import me.shouheng.notepal.model.Attachment;
import me.shouheng.notepal.model.MindSnagging;
import me.shouheng.notepal.model.Model;
import me.shouheng.notepal.model.ModelFactory;
import me.shouheng.notepal.model.Note;
import me.shouheng.notepal.model.Notebook;
import me.shouheng.notepal.model.enums.FabSortItem;
import me.shouheng.notepal.model.enums.ModelType;
import me.shouheng.notepal.model.enums.Status;
import me.shouheng.notepal.provider.AttachmentsStore;
import me.shouheng.notepal.provider.MindSnaggingStore;
import me.shouheng.notepal.provider.NotebookStore;
import me.shouheng.notepal.util.AttachmentHelper;
import me.shouheng.notepal.util.ColorUtils;
import me.shouheng.notepal.util.FragmentHelper;
import me.shouheng.notepal.util.IntentUtils;
import me.shouheng.notepal.util.LogUtils;
import me.shouheng.notepal.util.PreferencesUtils;
import me.shouheng.notepal.util.ToastUtils;
import me.shouheng.notepal.util.enums.MindSnaggingListType;
import me.shouheng.notepal.widget.tools.CustomRecyclerScrollViewListener;

import static android.support.v7.widget.RecyclerView.SCROLL_STATE_IDLE;

public class MainActivity extends CommonActivity<ActivityMainBinding> implements
        NotesFragment.OnNotesInteractListener,
        OnAttachingFileListener,
        OnSnagginsInteractListener {

    // region request codes
    private final int REQUEST_FAB_SORT = 0x0001;
    private final int REQUEST_ADD_NOTE = 0x0002;
    private final int REQUEST_ARCHIVE = 0x0003;
    private final int REQUEST_TRASH = 0x0004;
    private final int REQUEST_USER_INFO = 0x0005;
    private final int REQUEST_PASSWORD = 0x0006;
    private final int REQUEST_SEARCH = 0x0007;
    private final int REQUEST_NOTE_VIEW = 0x0008;
    // endregion

    private long onBackPressed;

    private PreferencesUtils preferencesUtils;

    private MindSnaggingDialog mindSnaggingDialog;
    private NotebookEditDialog notebookEditDialog;

    private RecyclerView.OnScrollListener onScrollListener;
    private NotesChangedReceiver notesChangedReceiver;

    private FloatingActionButton[] fabs;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_main;
    }

    @Override
    protected void beforeSetContentView() {
        setTranslucentStatusBar();
    }

    @Override
    protected void doCreateView(Bundle savedInstanceState) {
        preferencesUtils = PreferencesUtils.getInstance(this);

        IntroActivity.launchIfNecessary(this);

        checkPassword();

        regNoteChangeReceiver();
    }

    private void init() {
        handleIntent(getIntent());

        configToolbar();

        initHeaderView();

        initFloatButtons();
        initFabSortItems();

        initDrawerMenu();

        toNotesFragment();
    }

    private void checkPassword() {
        if (preferencesUtils.isPasswordRequired() && !PalmApp.isPasswordChecked()) {
            LockActivity.requireLaunch(this, REQUEST_PASSWORD);
        } else {
            init();
        }
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();

        // if the action is empty or the activity is recreated for theme change, don;t handle intent
        if (TextUtils.isEmpty(action) || recreateForThemeChange) return;

        switch (action) {
            case Constants.ACTION_SHORTCUT:
                intent.setClass(this, ContentActivity.class);
                startActivity(intent);
                break;
            case Constants.ACTION_ADD_NOTE:
                editNote(getNewNote());
                break;
            case Constants.ACTION_ADD_MIND:
                editMindSnagging(ModelFactory.getMindSnagging(this));
                break;
            case Constants.ACTION_WIDGET_LIST:
                Model model;
                if (intent.hasExtra(Constants.EXTRA_MODEL) && (model = (Model) intent.getSerializableExtra(Constants.EXTRA_MODEL)) != null) {
                    if (model instanceof Note) {
                        ContentActivity.startNoteViewForResult(this, (Note) model, null, REQUEST_NOTE_VIEW);
                    } else if (model instanceof MindSnagging) {
                        editMindSnagging((MindSnagging) model);
                    }
                }
                break;
            case Constants.ACTION_WIDGET_LAUNCH_APP:
                // do nothing just open the app.
                break;
            case Constants.ACTION_TAKE_PHOTO:
                ContentActivity.startAction(this, Constants.ACTION_TAKE_PHOTO, 0);
                break;
            case Constants.ACTION_ADD_SKETCH:
                ContentActivity.startAction(this, Constants.ACTION_ADD_SKETCH, 0);
                break;
            case Intent.ACTION_SEND:
            case Intent.ACTION_SEND_MULTIPLE:
            case Constants.INTENT_GOOGLE_NOW:
                handleThirdPart();
                break;
        }
    }

    private void handleThirdPart() {
        Intent i = getIntent();
        if (IntentUtils.checkAction(i, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Constants.INTENT_GOOGLE_NOW) && i.getType() != null) {
            ContentActivity.startThirdPartResult(this, i, REQUEST_ADD_NOTE);
        }
    }

    private void initHeaderView() {
        View header = getBinding().nav.inflateHeaderView(R.layout.activity_main_nav_header);
        ActivityMainNavHeaderBinding headerBinding = DataBindingUtil.bind(header);
        header.setOnClickListener(v -> {});
        header.setOnLongClickListener(v -> true);
        header.setOnClickListener(view -> startActivityForResult(UserInfoActivity.class, REQUEST_USER_INFO));
    }

    private void configToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_menu_white);
        if (!isDarkTheme()) toolbar.setPopupTheme(R.style.AppTheme_PopupOverlay);
    }

    // region fab
    private void initFloatButtons() {
        getBinding().menu.setMenuButtonColorNormal(accentColor());
        getBinding().menu.setMenuButtonColorPressed(accentColor());
        getBinding().menu.setOnMenuButtonLongClickListener(v -> {
            startActivityForResult(FabSortActivity.class, REQUEST_FAB_SORT);
            return false;
        });
        getBinding().menu.setOnMenuToggleListener(opened -> getBinding().rlMenuContainer.setVisibility(opened ? View.VISIBLE : View.GONE));
        getBinding().rlMenuContainer.setOnClickListener(view -> {
            getBinding().menu.close(true);
        });

        fabs = new FloatingActionButton[]{getBinding().fab1, getBinding().fab2, getBinding().fab3, getBinding().fab4, getBinding().fab5};

        for (int i=0; i<fabs.length; i++) {
            fabs[i].setColorNormal(accentColor());
            fabs[i].setColorPressed(accentColor());
            int finalI = i;
            fabs[i].setOnClickListener(view -> resolveFabClick(finalI));
        }

        onScrollListener = new CustomRecyclerScrollViewListener() {
            @Override
            public void show() {
                getBinding().menu.animate().translationY(0).setInterpolator(new DecelerateInterpolator(2)).start();
            }

            @Override
            public void hide() {
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) getBinding().menu.getLayoutParams();
                int fabMargin = lp.bottomMargin;
                getBinding().menu.animate().translationY(getBinding().menu.getHeight()+fabMargin).setInterpolator(new AccelerateInterpolator(2.0f)).start();
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                LogUtils.d("onScrollStateChanged: ");
                if (newState == SCROLL_STATE_IDLE){
                    LogUtils.d("onScrollStateChanged: SCROLL_STATE_IDLE");
                }
            }
        };
    }

    private void initFabSortItems() {
        try {
            List<FabSortItem> fabSortItems = preferencesUtils.getFabSortResult();
            for (int i=0; i<fabs.length; i++) {
                fabs[i].setImageDrawable(ColorUtils.tintDrawable(getResources().getDrawable(fabSortItems.get(i).iconRes), Color.WHITE));
                fabs[i].setLabelText(getString(fabSortItems.get(i).nameRes));
            }
        } catch (Exception e) {
            LogUtils.d("initFabSortItems, error occurred : " + e);
            PreferencesUtils.getInstance(this).setFabSortResult(PreferencesUtils.defaultFabOrders);
        }
    }

    private void resolveFabClick(int index) {
        getBinding().menu.close(true);
        FabSortItem fabSortItem = preferencesUtils.getFabSortResult().get(index);
        switch (fabSortItem) {
            case NOTE:
                editNote(getNewNote());
                break;
            case NOTEBOOK:
                editNotebook();
                break;
            case MIND_SNAGGING:
                editMindSnagging(ModelFactory.getMindSnagging(this));
                break;
            case DRAFT:
                ContentActivity.startAction(this, Constants.ACTION_ADD_SKETCH, 0);
                break;
            case FILE:
                ContentActivity.startAction(this, Constants.ACTION_ADD_FILES, 0);
                break;
            case CAPTURE:
                ContentActivity.startAction(this, Constants.ACTION_TAKE_PHOTO, 0);
                break;
        }
    }

    private void editNote(@NonNull final Note note) {
        PermissionUtils.checkStoragePermission(this, () -> {
            ContentActivity.startNoteEditForResult(this, note, null, REQUEST_ADD_NOTE);
        });
    }

    private Note getNewNote() {
        Note note = ModelFactory.getNote(this);
        Notebook notebook;
        if (isNotesFragment() && (notebook = ((NotesFragment) getCurrentFragment()).getNotebook()) != null) {
            note.setParentCode(notebook.getCode());
            note.setTreePath(notebook.getTreePath() + "|" + note.getCode());
        } else {
            // The default tree path of note is itself
            note.setTreePath(String.valueOf(note.getCode()));
        }
        return note;
    }

    private void editNotebook() {
        Notebook notebook = ModelFactory.getNotebook(this);
        notebookEditDialog = NotebookEditDialog.newInstance(this, notebook, (notebookName, notebookColor) -> {
            notebook.setTitle(notebookName);
            notebook.setColor(notebookColor);
            notebook.setCount(0);
            notebook.setTreePath(String.valueOf(notebook.getCode()));
            Notebook parent;
            if (isNotesFragment() && (parent = ((NotesFragment) getCurrentFragment()).getNotebook()) != null) {
                notebook.setParentCode(parent.getCode());
                notebook.setTreePath(parent.getTreePath() + "|" + notebook.getCode());
            }
            NotebookStore.getInstance(this).saveModel(notebook);
            Fragment fragment = getCurrentFragment();
            if (fragment != null && fragment instanceof NotesFragment) {
                ((NotesFragment) fragment).reload();
            }
        });
        notebookEditDialog.show(getSupportFragmentManager(), "NotebookEditDialog");
    }

    private void editMindSnagging(@NonNull MindSnagging param) {
        mindSnaggingDialog = new MindSnaggingDialog.Builder()
                .setMindSnagging(param)
                .setOnAddAttachmentListener(mindSnagging -> showAttachmentPicker())
                .setOnAttachmentClickListener(this::resolveAttachmentClick)
                .setOnConfirmListener(this::saveMindSnagging)
                .build();
        mindSnaggingDialog.show(getSupportFragmentManager(), "mind snagging");
    }

    private void resolveAttachmentClick(Attachment attachment) {
        AttachmentHelper.resolveClickEvent(
                this,
                attachment,
                Arrays.asList(attachment),
                "");
    }

    private void saveMindSnagging(MindSnagging mindSnagging, Attachment attachment) {
        if (attachment != null && AttachmentsStore.getInstance(this).isNewModel(attachment.getCode())) {
            attachment.setModelCode(mindSnagging.getCode());
            attachment.setModelType(ModelType.MIND_SNAGGING);
            AttachmentsStore.getInstance(this).saveModel(attachment);
        }

        if (MindSnaggingStore.getInstance(this).isNewModel(mindSnagging.getCode())) {
            MindSnaggingStore.getInstance(this).saveModel(mindSnagging);
        } else {
            MindSnaggingStore.getInstance(this).update(mindSnagging);
        }

        ToastUtils.makeToast(this, R.string.text_save_successfully);

        if (isSnaggingFragment()) ((SnaggingsFragment) getCurrentFragment()).addSnagging(mindSnagging);
    }

    private void showAttachmentPicker() {
        new AttachmentPickerDialog.Builder()
                .setAddLinkVisible(false)
                .setRecordVisible(false)
                .setVideoVisible(false)
                .build().show(getSupportFragmentManager(), "Attachment picker");
    }
    // endregion

    // region drawer
    private void setDrawerLayoutLocked(boolean lockDrawer){
        getBinding().drawerLayout.setDrawerLockMode(lockDrawer ?
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    private void initDrawerMenu() {
        getBinding().nav.getMenu().findItem(R.id.nav_notes).setChecked(true);
        getBinding().nav.setNavigationItemSelectedListener(menuItem -> {
            getBinding().drawerLayout.closeDrawers();
            switch (menuItem.getItemId()) {
                case R.id.nav_notes:
                case R.id.nav_minds:
                case R.id.nav_notices:
                    menuItem.setChecked(true);
                    break;
            }
            execute(menuItem);
            return true;
        });
    }

    private void execute(final MenuItem menuItem) {
        new Handler().postDelayed(() -> {
            switch (menuItem.getItemId()) {
                case R.id.nav_sync:
                    NoticeDialog.newInstance().show(getSupportFragmentManager(), "NoticeDialog");
                    break;
                case R.id.nav_settings:
                    startActivity(SettingsActivity.class);
                    break;
                case R.id.nav_notes:
                    toNotesFragment();
                    break;
                case R.id.nav_minds:
                    toSnaggingsFragment(true);
                    break;
                case R.id.nav_archive:
                    startActivityForResult(ArchiveActivity.class, REQUEST_ARCHIVE);
                    break;
                case R.id.nav_trash:
                    startActivityForResult(TrashedActivity.class, REQUEST_TRASH);
                    break;
            }
        }, 500);
    }

    private void toNotesFragment() {
        if (isNotesFragment()) return;
        NotesFragment notesFragment = NotesFragment.newInstance(null, Status.NORMAL);
        notesFragment.setScrollListener(onScrollListener);
        FragmentHelper.replace(this, notesFragment, R.id.fragment_container);
        new Handler().postDelayed(() -> getBinding().nav.getMenu().findItem(R.id.nav_notes).setChecked(true), 300);
    }

    private void toSnaggingsFragment(boolean checkDuplicate) {
        if (checkDuplicate && getCurrentFragment() instanceof SnaggingsFragment) return;
        SnaggingsFragment snaggingsFragment = SnaggingsFragment.newInstance();
        snaggingsFragment.setScrollListener(onScrollListener);
        FragmentHelper.replace(this, snaggingsFragment, R.id.fragment_container);
        new Handler().postDelayed(() -> getBinding().nav.getMenu().findItem(R.id.nav_minds).setChecked(true), 300);
    }

    private Fragment getCurrentFragment(){
        return getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    }

    private boolean isNotesFragment(){
        return getCurrentFragment() instanceof NotesFragment;
    }

    private boolean isSnaggingFragment() {
        return getCurrentFragment() instanceof SnaggingsFragment;
    }

    private boolean isDashboard() {
        Fragment currentFragment = getCurrentFragment();
        return currentFragment instanceof NotesFragment || currentFragment instanceof SnaggingsFragment;
    }
    // endregion

    // region notes change receiver
    private void regNoteChangeReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_NOTE_CHANGE_BROADCAST);
        notesChangedReceiver = new NotesChangedReceiver();
        registerReceiver(notesChangedReceiver, filter);
    }

    private class NotesChangedReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (isNotesFragment()) ((NotesFragment) getCurrentFragment()).reload();
        }
    }
    // endregion

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                Fragment fragment = getCurrentFragment();
                if (!fragment.onOptionsItemSelected(item)) {
                    getBinding().drawerLayout.openDrawer(GravityCompat.START);
                }
                return true;
            }
            case R.id.action_search:
                SearchActivity.startActivityForResult(this, REQUEST_SEARCH);
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) return;

        handleAttachmentResult(requestCode, data);

        switch (requestCode) {
            case REQUEST_FAB_SORT:
                initFabSortItems();
                break;
            case REQUEST_NOTE_VIEW:
            case REQUEST_ADD_NOTE:
                if (isNotesFragment()) ((NotesFragment) getCurrentFragment()).reload();
                break;
            case REQUEST_TRASH:
                updateListIfNecessary();
                break;
            case REQUEST_ARCHIVE: // todo update event
                updateListIfNecessary();
                break;
            case REQUEST_SEARCH:
                updateListIfNecessary();
                break;
            case REQUEST_PASSWORD:
                init();
                break;
        }
    }

    private void updateListIfNecessary() {
        if (isNotesFragment()) ((NotesFragment) getCurrentFragment()).reload();
        if (isSnaggingFragment()) ((SnaggingsFragment) getCurrentFragment()).reload();
    }

    private void handleAttachmentResult(int requestCode, Intent data) {
        AttachmentHelper.resolveResult(this, requestCode, data, attachment ->
                mindSnaggingDialog.setAttachment(attachment));
    }

    @Override
    public void onBackPressed() {
        if (isDashboard()){
            if (getBinding().drawerLayout.isDrawerOpen(GravityCompat.START)){
                getBinding().drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                if (getBinding().menu.isOpened()) {
                    getBinding().menu.close(true);
                    return;
                }
                if (isNotesFragment()) {
                    if (((NotesFragment) getCurrentFragment()).isTopStack()) {
                        againExit();
                    } else {
                        super.onBackPressed();
                    }
                } else {
                    toNotesFragment();
                }
            }
        } else {
            super.onBackPressed();
        }
    }

    private void againExit() {
        long TIME_INTERVAL_BACK = 2000;
        if (onBackPressed + TIME_INTERVAL_BACK > System.currentTimeMillis()) {
            super.onBackPressed();
            return;
        } else {
            ToastUtils.makeToast(this, R.string.text_tab_again_exit);
        }
        onBackPressed = System.currentTimeMillis();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(notesChangedReceiver);
    }

    @Override
    public void onColorSelection(@NonNull ColorChooserDialog dialog, int selectedColor) {
        if (notebookEditDialog != null) {
            notebookEditDialog.updateUIBySelectedColor(selectedColor);
        }
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment instanceof NotesFragment) {
            ((NotesFragment) currentFragment).setSelectedColor(selectedColor);
        }
    }

    @Override
    public void onNotebookSelected(Notebook notebook) {
        NotesFragment notesFragment = NotesFragment.newInstance(notebook, Status.NORMAL);
        notesFragment.setScrollListener(onScrollListener);
        FragmentHelper.replaceWithCallback(this, notesFragment, R.id.fragment_container);
    }

    @Override
    public void onActivityAttached(boolean isTopStack) {
        setDrawerLayoutLocked(!isTopStack);
    }

    @Override
    public void onAttachingFileErrorOccurred(Attachment attachment) {
        ToastUtils.makeToast(R.string.failed_to_save_attachment);
    }

    @Override
    public void onAttachingFileFinished(Attachment attachment) {
        mindSnaggingDialog.setAttachment(attachment);
    }

    @Override
    public void onListTypeChanged(MindSnaggingListType listType) {
        toSnaggingsFragment(false);
    }
}
