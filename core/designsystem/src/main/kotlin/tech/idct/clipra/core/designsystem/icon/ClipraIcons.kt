package tech.idct.clipra.core.designsystem.icon

import androidx.annotation.DrawableRes
import tech.idct.clipra.core.designsystem.R

/**
 * The icon set: stroke icons on a 24 grid, drawn from the design snapshots in design/static/.
 * Tint them with [ClipraIcon]. [Logo] keeps its own colours and [Crown] is only for Clipra Pro.
 */
object ClipraIcons {
    @DrawableRes val Add = R.drawable.designsystem_ic_add

    @DrawableRes val AlignCenter = R.drawable.designsystem_ic_align_center

    @DrawableRes val AlignLeft = R.drawable.designsystem_ic_align_left

    @DrawableRes val AlignRight = R.drawable.designsystem_ic_align_right

    @DrawableRes val Audio = R.drawable.designsystem_ic_audio

    @DrawableRes val Back = R.drawable.designsystem_ic_back

    @DrawableRes val BeforeAfter = R.drawable.designsystem_ic_before_after

    @DrawableRes val Captions = R.drawable.designsystem_ic_captions

    @DrawableRes val Check = R.drawable.designsystem_ic_check

    @DrawableRes val ChevronDown = R.drawable.designsystem_ic_chevron_down

    @DrawableRes val ChevronLeft = R.drawable.designsystem_ic_chevron_left

    @DrawableRes val ChevronRight = R.drawable.designsystem_ic_chevron_right

    @DrawableRes val Close = R.drawable.designsystem_ic_close

    @DrawableRes val Color = R.drawable.designsystem_ic_color

    @DrawableRes val CompareArrows = R.drawable.designsystem_ic_compare_arrows

    @DrawableRes val Copy = R.drawable.designsystem_ic_copy

    @DrawableRes val Crop = R.drawable.designsystem_ic_crop

    @DrawableRes val Crown = R.drawable.designsystem_ic_crown

    @DrawableRes val Cutout = R.drawable.designsystem_ic_cutout

    @DrawableRes val Delete = R.drawable.designsystem_ic_delete

    @DrawableRes val Download = R.drawable.designsystem_ic_download

    @DrawableRes val Duplicate = R.drawable.designsystem_ic_duplicate

    @DrawableRes val EditByText = R.drawable.designsystem_ic_edit_by_text

    @DrawableRes val Effects = R.drawable.designsystem_ic_effects

    @DrawableRes val Export = R.drawable.designsystem_ic_export

    /** Picks the chroma key's colour from the picture (Cutout board, #51). */
    @DrawableRes val Eyedropper = R.drawable.designsystem_ic_eyedropper

    @DrawableRes val Face = R.drawable.designsystem_ic_face

    @DrawableRes val Fades = R.drawable.designsystem_ic_fades

    @DrawableRes val FilterCompare = R.drawable.designsystem_ic_filter_compare

    @DrawableRes val Filters = R.drawable.designsystem_ic_filters

    @DrawableRes val Flip = R.drawable.designsystem_ic_flip

    @DrawableRes val Forward = R.drawable.designsystem_ic_forward

    @DrawableRes val Freeze = R.drawable.designsystem_ic_freeze

    @DrawableRes val Grip = R.drawable.designsystem_ic_grip

    @DrawableRes val Hidden = R.drawable.designsystem_ic_hidden

    @DrawableRes val Image = R.drawable.designsystem_ic_image

    @DrawableRes val Keyframe = R.drawable.designsystem_ic_keyframe

    @DrawableRes val Layers = R.drawable.designsystem_ic_layers

    @DrawableRes val Link = R.drawable.designsystem_ic_link

    @DrawableRes val Lock = R.drawable.designsystem_ic_lock

    @DrawableRes val Logo = R.drawable.designsystem_ic_logo

    @DrawableRes val Mask = R.drawable.designsystem_ic_mask

    @DrawableRes val MaskNone = R.drawable.designsystem_ic_mask_none

    @DrawableRes val MaskOval = R.drawable.designsystem_ic_mask_oval

    @DrawableRes val MaskRectangle = R.drawable.designsystem_ic_mask_rectangle

    @DrawableRes val Merge = R.drawable.designsystem_ic_merge

    @DrawableRes val Microphone = R.drawable.designsystem_ic_microphone

    @DrawableRes val Mosaic = R.drawable.designsystem_ic_mosaic

    @DrawableRes val Music = R.drawable.designsystem_ic_music

    @DrawableRes val Muted = R.drawable.designsystem_ic_muted

    @DrawableRes val Notification = R.drawable.designsystem_ic_notification

    @DrawableRes val OnDevice = R.drawable.designsystem_ic_on_device

    @DrawableRes val Pause = R.drawable.designsystem_ic_pause

    @DrawableRes val Play = R.drawable.designsystem_ic_play

    @DrawableRes val Projects = R.drawable.designsystem_ic_projects

    @DrawableRes val Redo = R.drawable.designsystem_ic_redo

    @DrawableRes val Reframe = R.drawable.designsystem_ic_reframe

    @DrawableRes val Replace = R.drawable.designsystem_ic_replace

    @DrawableRes val Reset = R.drawable.designsystem_ic_reset

    @DrawableRes val Reverse = R.drawable.designsystem_ic_reverse

    @DrawableRes val Ripple = R.drawable.designsystem_ic_ripple

    @DrawableRes val Search = R.drawable.designsystem_ic_search

    @DrawableRes val Settings = R.drawable.designsystem_ic_settings

    @DrawableRes val Share = R.drawable.designsystem_ic_share

    @DrawableRes val SkipBack = R.drawable.designsystem_ic_skip_back

    @DrawableRes val SkipForward = R.drawable.designsystem_ic_skip_forward

    @DrawableRes val Snap = R.drawable.designsystem_ic_snap

    @DrawableRes val Speed = R.drawable.designsystem_ic_speed

    @DrawableRes val Split = R.drawable.designsystem_ic_split

    @DrawableRes val Stabilize = R.drawable.designsystem_ic_stabilize

    @DrawableRes val Text = R.drawable.designsystem_ic_text

    @DrawableRes val Transition = R.drawable.designsystem_ic_transition

    @DrawableRes val Translate = R.drawable.designsystem_ic_translate

    @DrawableRes val Undo = R.drawable.designsystem_ic_undo

    @DrawableRes val Unlock = R.drawable.designsystem_ic_unlock

    @DrawableRes val Video = R.drawable.designsystem_ic_video

    @DrawableRes val Visible = R.drawable.designsystem_ic_visible

    @DrawableRes val Voice = R.drawable.designsystem_ic_voice

    @DrawableRes val Volume = R.drawable.designsystem_ic_volume

    @DrawableRes val ZoomIn = R.drawable.designsystem_ic_zoom_in

    @DrawableRes val ZoomOut = R.drawable.designsystem_ic_zoom_out

    /** Every icon with its name, for the debug gallery. */
    val all: List<Pair<String, Int>> = listOf(
        "add" to Add,
        "align_center" to AlignCenter,
        "align_left" to AlignLeft,
        "align_right" to AlignRight,
        "audio" to Audio,
        "back" to Back,
        "before_after" to BeforeAfter,
        "captions" to Captions,
        "check" to Check,
        "chevron_down" to ChevronDown,
        "chevron_left" to ChevronLeft,
        "chevron_right" to ChevronRight,
        "close" to Close,
        "color" to Color,
        "compare_arrows" to CompareArrows,
        "copy" to Copy,
        "crop" to Crop,
        "crown" to Crown,
        "cutout" to Cutout,
        "delete" to Delete,
        "download" to Download,
        "duplicate" to Duplicate,
        "edit_by_text" to EditByText,
        "effects" to Effects,
        "export" to Export,
        "eyedropper" to Eyedropper,
        "face" to Face,
        "fades" to Fades,
        "filter_compare" to FilterCompare,
        "filters" to Filters,
        "flip" to Flip,
        "forward" to Forward,
        "freeze" to Freeze,
        "grip" to Grip,
        "hidden" to Hidden,
        "image" to Image,
        "keyframe" to Keyframe,
        "layers" to Layers,
        "link" to Link,
        "lock" to Lock,
        "logo" to Logo,
        "mask" to Mask,
        "mask_none" to MaskNone,
        "mask_oval" to MaskOval,
        "mask_rectangle" to MaskRectangle,
        "merge" to Merge,
        "microphone" to Microphone,
        "mosaic" to Mosaic,
        "music" to Music,
        "muted" to Muted,
        "notification" to Notification,
        "on_device" to OnDevice,
        "pause" to Pause,
        "play" to Play,
        "projects" to Projects,
        "redo" to Redo,
        "reframe" to Reframe,
        "replace" to Replace,
        "reset" to Reset,
        "reverse" to Reverse,
        "ripple" to Ripple,
        "search" to Search,
        "settings" to Settings,
        "share" to Share,
        "skip_back" to SkipBack,
        "skip_forward" to SkipForward,
        "snap" to Snap,
        "speed" to Speed,
        "split" to Split,
        "stabilize" to Stabilize,
        "text" to Text,
        "transition" to Transition,
        "translate" to Translate,
        "undo" to Undo,
        "unlock" to Unlock,
        "video" to Video,
        "visible" to Visible,
        "voice" to Voice,
        "volume" to Volume,
        "zoom_in" to ZoomIn,
        "zoom_out" to ZoomOut,
    )
}
