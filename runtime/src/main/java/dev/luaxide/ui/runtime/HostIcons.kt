package dev.luaxide.ui.runtime

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.graphics.vector.ImageVector

/** Curated Material icon names for `ui.icon` / listitem / tab. Unknown names fall back. */
internal object HostIcons {
    val fallback: ImageVector = Icons.AutoMirrored.Filled.HelpOutline
    val imagePlaceholder: ImageVector = Icons.Outlined.Image
    val imageBroken: ImageVector = Icons.Filled.BrokenImage
    val chevron: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight

    fun resolve(name: String): ImageVector? {
        if (name.isBlank()) return null
        val key = name.trim().lowercase().replace('-', '_').replace(' ', '_')
        return MAP[key]
    }

    private val MAP: Map<String, ImageVector> = mapOf(
        "home" to Icons.Filled.Home,
        "back" to Icons.AutoMirrored.Filled.ArrowBack,
        "arrow_back" to Icons.AutoMirrored.Filled.ArrowBack,
        "arrow_forward" to Icons.AutoMirrored.Filled.ArrowForward,
        "chevron_right" to Icons.AutoMirrored.Filled.KeyboardArrowRight,
        "chevron_left" to Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        "close" to Icons.Filled.Close,
        "add" to Icons.Filled.Add,
        "check" to Icons.Filled.Check,
        "done" to Icons.Filled.Done,
        "search" to Icons.Filled.Search,
        "settings" to Icons.Filled.Settings,
        "star" to Icons.Filled.Star,
        "person" to Icons.Filled.Person,
        "favorite" to Icons.Filled.Favorite,
        "info" to Icons.Filled.Info,
        "help" to Icons.AutoMirrored.Filled.Help,
        "warning" to Icons.Filled.Warning,
        "error" to Icons.Filled.Error,
        "delete" to Icons.Filled.Delete,
        "edit" to Icons.Filled.Edit,
        "share" to Icons.Filled.Share,
        "more" to Icons.Filled.MoreVert,
        "menu" to Icons.Filled.Menu,
        "image" to Icons.Filled.Image,
        "list" to Icons.AutoMirrored.Filled.List,
        "check_box" to Icons.Filled.CheckBox,
        "check_box_outline" to Icons.Filled.CheckBoxOutlineBlank,
        "notifications" to Icons.Filled.Notifications,
        "lock" to Icons.Filled.Lock,
        "mail" to Icons.Filled.Mail,
        "email" to Icons.Filled.Email,
        "phone" to Icons.Filled.Phone,
        "send" to Icons.AutoMirrored.Filled.Send,
        "refresh" to Icons.Filled.Refresh,
        "play" to Icons.Filled.PlayArrow,
        "pause" to Icons.Filled.Pause,
        "stop" to Icons.Filled.Stop,
        "cancel" to Icons.Filled.Cancel,
        "visibility" to Icons.Filled.Visibility,
        "visibility_off" to Icons.Filled.VisibilityOff,
        "folder" to Icons.Filled.Folder,
        "file" to Icons.AutoMirrored.Filled.InsertDriveFile,
        "link" to Icons.Filled.Link,
        "calendar" to Icons.Filled.CalendarMonth,
        "camera" to Icons.Filled.PhotoCamera,
        "location" to Icons.Filled.LocationOn,
        "build" to Icons.Filled.Build,
        "check_circle" to Icons.Filled.CheckCircle,
    )
}
