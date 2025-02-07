package com.example.textselector.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.textselector.R
import com.example.textselector.data.SavedSelection
import com.example.textselector.databinding.ActivityMainBinding
import com.example.textselector.ui.viewmodel.MainViewModel
import com.example.textselector.ui.viewmodel.MainViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(this) }
    private var searchView: SearchView? = null
    private var searchMenuItem: MenuItem? = null
    private var wasSearchExpanded = false
    private var savedSearchQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("TextSelectorPrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("isDarkMode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let {
            wasSearchExpanded = it.getBoolean("wasSearchExpanded", false)
            savedSearchQuery = it.getString("savedSearchQuery")
        }

        setupToolbar()
        setupTextArea()
        setupSaveButton()
        setupSearchNavigation()
        observeViewModel()

        // Handle SEND intent
        if (intent?.action == "android.intent.action.SEND" && intent.type == "text/plain") {
            intent.getStringExtra("android.intent.extra.TEXT")?.let {
                binding.pinnedEditText.setText(it)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(navInsets.left, view.paddingTop, navInsets.right, navInsets.bottom)
            // Use safe call in case saveFab is missing in some layouts
            binding.saveFab?.translationY = -imeInsets.bottom.toFloat()
            binding.searchNavigation.translationY = -imeInsets.bottom.toFloat()
            insets
        }

        binding.pinnedEditText.selectionChangeListener = { start, end ->
            binding.saveFab?.visibility = if (end - start > 0) View.VISIBLE else View.GONE
        }
        binding.pinnedEditText.onPinChanged = { updatePinBanner() }
        binding.pinnedEditText.onSearchCleared = { updateSearchNavigation(clear = true) }

        binding.pinnedEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.pinnedEditText.nextSearchResult()
                updateSearchNavigation()
                true
            } else false
        }
    }

    private fun observeViewModel() {
        viewModel.savedSelections.observe(this, Observer {
            // Update UI if needed.
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("wasSearchExpanded", searchMenuItem?.isActionViewExpanded ?: false)
        outState.putString("savedSearchQuery", searchView?.query?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        searchMenuItem = menu.findItem(R.id.action_search)
        searchView = searchMenuItem?.actionView as? SearchView
        searchView?.apply {
            queryHint = getString(R.string.search_term)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    binding.pinnedEditText.updateSearch(newText.orEmpty())
                    updateSearchNavigation()
                    return true
                }
            })
        }
        if (wasSearchExpanded) {
            searchMenuItem?.expandActionView()
            searchView?.setQuery(savedSearchQuery, false)
        }
        searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView?.requestFocus()
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.pinnedEditText.clearSearchHighlights()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_library -> {
                showSavedSelections()
                return true
            }
            R.id.action_toggle_theme -> {
                toggleTheme()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.findViewById<TextView>(R.id.toolbarTitle).apply {
            text = getString(R.string.app_name)
            setOnClickListener { showAboutDialog() }
        }
    }

    private fun setupTextArea() {
        if (binding.pinnedEditText.text.isNullOrEmpty())
            binding.pinnedEditText.setText("")
    }

    private fun setupSaveButton() {
        // Use safe calls so that if saveFab is absent (e.g. in landscape) it won’t crash.
        binding.saveFab?.visibility = View.GONE
        binding.saveFab?.setOnClickListener {
            animateSaveButton { showSaveBottomSheet() }
        }
    }

    private fun animateSaveButton(onAnimationEnd: () -> Unit) {
        binding.saveFab?.animate()?.apply {
            scaleX(0.8f).scaleY(0.8f).setDuration(100)
            withEndAction {
                binding.saveFab?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)
                    ?.withEndAction(onAnimationEnd)
                    ?.start()
            }
            start()
        }
    }

    // Implements search navigation using arrow buttons if available.
    private fun setupSearchNavigation() {
        binding.searchNavigation.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            binding.pinnedEditText.previousSearchResult()
            updateSearchNavigation()
        }
        binding.searchNavigation.findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            binding.pinnedEditText.nextSearchResult()
            updateSearchNavigation()
        }
    }

    private fun updateSearchNavigation(clear: Boolean = false) {
        val count = binding.pinnedEditText.getSearchResultsCount?.invoke() ?: 0
        val current = binding.pinnedEditText.getCurrentSearchIndex?.invoke() ?: 0
        binding.txtSearchCount?.text = if (count > 0) "$current / $count" else ""
    }

    private fun showSaveBottomSheet() {
        val selectionText = binding.pinnedEditText.text?.substring(
            binding.pinnedEditText.selectionStart,
            binding.pinnedEditText.selectionEnd
        ) ?: return

        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_save, null)
        bottomSheetDialog.setContentView(sheetView)

        val nameInput = sheetView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameInput)
        val previewText = sheetView.findViewById<TextView>(R.id.previewText)
        val saveButton = sheetView.findViewById<TextView>(R.id.saveButton)
        val cancelButton = sheetView.findViewById<TextView>(R.id.cancelButton)

        val defaultName = selectionText.take(50).replace("\n", " ").split(" ").take(5).joinToString(" ")
        nameInput.setText(defaultName)
        previewText.text = selectionText

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().ifBlank { defaultName }
            val selection = SavedSelection(name = name, text = selectionText)
            viewModel.saveSelection(selection)
            bottomSheetDialog.dismiss()
            showSnackbar(getString(R.string.selection_saved))
        }
        cancelButton.setOnClickListener { bottomSheetDialog.dismiss() }
        bottomSheetDialog.show()
    }

    private fun showSavedSelections() {
        val selections = viewModel.savedSelections.value
        if (selections.isNullOrEmpty()) {
            showSnackbar(getString(R.string.no_selections))
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_saved_selections, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.savedSelectionsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val alertDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val adapter = SavedSelectionsAdapter(selections,
            onItemClick = { selection ->
                binding.pinnedEditText.clearSelectionPins()
                binding.pinnedEditText.clearSearchHighlights()
                binding.pinnedEditText.setText(selection.text)
                alertDialog.dismiss()
            },
            onDeleteClick = { selection -> showDeleteConfirmation(selection) },
            onEditClick = { selection -> showEditDialog(selection) }
        )
        recyclerView.adapter = adapter
        alertDialog.show()
    }

    private fun showDeleteConfirmation(selection: SavedSelection) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_selection))
            .setMessage(getString(R.string.delete_confirmation, selection.name))
            .setPositiveButton(getString(R.string.delete)) { dialog, which ->
                viewModel.deleteSelection(selection)
                showSnackbar(getString(R.string.selection_deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDialog(selection: SavedSelection) {
        val dialogView: View = layoutInflater.inflate(R.layout.dialog_edit_selection, null)
        val nameInput = dialogView.findViewById<android.widget.EditText>(R.id.nameInput)
        nameInput.setText(selection.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_selection))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { dialog: DialogInterface, which: Int ->
                val updatedSelection = selection.copy(
                    name = nameInput.text.toString().ifBlank { selection.name }
                )
                viewModel.updateSelection(updatedSelection)
                showSnackbar(getString(R.string.selection_updated))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences("TextSelectorPrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("isDarkMode", false)
        prefs.edit().putBoolean("isDarkMode", !isDarkMode).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun updatePinBanner() {
        binding.bottomBanner.visibility =
            if (binding.pinnedEditText.selectionStart != binding.pinnedEditText.selectionEnd)
                View.VISIBLE else View.GONE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        val aboutHtml = """
            <h1>Tobias Fankhauser</h1>
            <p>Visit my <a href="https://github.com/TobiFank">GitHub</a> or <a href="https://www.linkedin.com/in/tobias-fankhauser">LinkedIn</a>.</p>
            <p>Thank you for using this app!</p>
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about))
            .setMessage(Html.fromHtml(aboutHtml, Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
}
