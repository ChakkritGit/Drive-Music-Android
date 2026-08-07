package com.drivemusic.android.ui

import androidx.annotation.DrawableRes
import com.drivemusic.android.R

/**
 * The app's icons: Material Symbols Outlined, weight 400, optical size 24, throughout.
 *
 * Not `Icons.Default.*`. That set is Material Icons, which accumulated over years from different
 * drawing conventions — stroke weights and optical volumes vary between glyphs, so a row of them
 * never reads as one family however carefully they are sized. Material Symbols is drawn from one
 * variable font at one setting, so every glyph here shares a weight and an optical size by
 * construction rather than by luck.
 *
 * Held as drawable ids rather than as `ImageVector`s so enums and data classes can carry an icon
 * without needing a composition to build one.
 */
object AppIcons {
    @DrawableRes val AccountCircle = R.drawable.ic_account_circle
    @DrawableRes val Add = R.drawable.ic_add
    @DrawableRes val ArrowBack = R.drawable.ic_arrow_back
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val ArrowDropDown = R.drawable.ic_arrow_drop_down
    @DrawableRes val ArrowDropUp = R.drawable.ic_arrow_drop_up
    @DrawableRes val AutoAwesome = R.drawable.ic_auto_awesome
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val CloudDone = R.drawable.ic_cloud_done
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Contrast = R.drawable.ic_contrast
    @DrawableRes val Delete = R.drawable.ic_delete
    @DrawableRes val Description = R.drawable.ic_description
    @DrawableRes val Download = R.drawable.ic_download
    @DrawableRes val Favorite = R.drawable.ic_favorite
    @DrawableRes val FavoriteBorder = R.drawable.ic_favorite_border
    @DrawableRes val Equalizer = R.drawable.ic_equalizer
    @DrawableRes val Folder = R.drawable.ic_folder
    @DrawableRes val HeartBroken = R.drawable.ic_heart_broken
    @DrawableRes val Home = R.drawable.ic_home
    @DrawableRes val Insights = R.drawable.ic_insights
    @DrawableRes val KeyboardArrowDown = R.drawable.ic_keyboard_arrow_down
    @DrawableRes val Language = R.drawable.ic_language
    @DrawableRes val LibraryMusic = R.drawable.ic_library_music
    @DrawableRes val List = R.drawable.ic_list
    @DrawableRes val Logout = R.drawable.ic_logout
    @DrawableRes val Mic = R.drawable.ic_mic
    @DrawableRes val MoreVert = R.drawable.ic_more_vert
    @DrawableRes val MusicNote = R.drawable.ic_music_note
    @DrawableRes val OpenInNew = R.drawable.ic_open_in_new
    @DrawableRes val Pause = R.drawable.ic_pause
    @DrawableRes val PlayArrow = R.drawable.ic_play_arrow
    @DrawableRes val PlaylistAdd = R.drawable.ic_playlist_add
    @DrawableRes val PlaylistPlay = R.drawable.ic_playlist_play
    @DrawableRes val QueuePlayNext = R.drawable.ic_queue_play_next
    @DrawableRes val Repeat = R.drawable.ic_repeat
    @DrawableRes val RepeatOne = R.drawable.ic_repeat_one
    @DrawableRes val Schedule = R.drawable.ic_schedule
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val Settings = R.drawable.ic_settings
    @DrawableRes val Shield = R.drawable.ic_shield
    @DrawableRes val Shuffle = R.drawable.ic_shuffle
    @DrawableRes val SkipNext = R.drawable.ic_skip_next
    @DrawableRes val SkipPrevious = R.drawable.ic_skip_previous
    @DrawableRes val Sort = R.drawable.ic_sort
}
