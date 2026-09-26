package com.plexus.app

import android.content.Context


object ProfileManager {

    private const val PREFS_NAME =
        "plexus_profile"

    private const val KEY_SETUP_COMPLETE =
        "setup_complete"

    private const val KEY_DISPLAY_NAME =
        "display_name"


    /*
     * Has the user already completed
     * the first-launch Plexus setup?
     */
    fun isSetupComplete(
        context: Context
    ): Boolean {

        return context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                KEY_SETUP_COMPLETE,
                false
            )
    }


    /*
     * Returns the user's saved Plexus name.
     *
     * null means no usable name has
     * been stored yet.
     */
    fun getDisplayName(
        context: Context
    ): String? {

        val storedName =
            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getString(
                    KEY_DISPLAY_NAME,
                    null
                )
                ?.trim()


        return storedName
            ?.takeIf {
                it.isNotEmpty()
            }
            ?.take(64)
    }


    /*
     * Called only when the user presses
     * Continue on the first-launch screen.
     */
    fun completeInitialSetup(
        context: Context,
        displayName: String
    ): Boolean {

        val cleanName =
            displayName.trim().take(64)


        if (
            cleanName.isEmpty()
        ) {

            return false
        }


        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_DISPLAY_NAME,
                cleanName
            )
            .putBoolean(
                KEY_SETUP_COMPLETE,
                true
            )
            .apply()


        return true
    }


    /*
     * Used later from the Profile screen
     * when the user changes their own name.
     *
     * This changes ONLY the human-readable
     * profile name.
     *
     * Identity keys and nodeId are untouched.
     */
    fun updateDisplayName(
        context: Context,
        displayName: String
    ): Boolean {

        val cleanName =
            displayName.trim().take(64)


        if (
            cleanName.isEmpty()
        ) {

            return false
        }


        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_DISPLAY_NAME,
                cleanName
            )
            .apply()


        return true
    }
}
