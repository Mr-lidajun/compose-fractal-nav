package com.zachklipp.fractalnav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.zachklipp.galaxyapp.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class FractalNavStateImpl : FractalNavState, FractalNavScope, FractalParent {
    // These do not need to be backed by snapshot state because they're set in a side effect.
    lateinit var coroutineScope: CoroutineScope
    override lateinit var zoomAnimationSpecFactory: () -> AnimationSpec<Float>

    private val children = mutableMapOf<String, FractalChild>()
    private val zoomFactorAnimatable = Animatable(0f)
    var viewportCoordinates: LayoutCoordinates? = null
    var scaledContentCoordinates: LayoutCoordinates? by mutableStateOf(null)
    override val zoomFactor: Float by zoomFactorAnimatable.asState()
    val isFullyZoomedIn by derivedStateOf { zoomFactor == 1f }
    override var zoomDirection: ZoomDirection? by mutableStateOf(null)
        private set

    /**
     * The first time the content, and thus this modifier, is composed after
     * starting a zoom-out, this modifier will run before the placeholder has been
     * placed, so we can't calculate the scale. We'll return early, and because
     * the above layer will have set alpha to 0, it doesn't matter that the content
     * is initially in the wrong place/size. The subsequent frame it will be able to
     * calculate correctly.
     */
    private var isContentBeingScaled by mutableStateOf(false)

    /**
     * The child that is currently either zoomed in or out, or fully zoomed in. Null when fully
     * zoomed out.
     */
    override var activeChild: FractalChild? by mutableStateOf(null)
    private val placeholderBounds: Rect?
        get() {
            val coords = scaledContentCoordinates?.takeIf { it.isAttached } ?: return null
            val childCoords =
                activeChild?.placeholderCoordinates?.takeIf { it.isAttached } ?: return null
            return coords.localBoundingBoxOf(childCoords, clipBounds = false)
        }
    private var childPlaceholderSize: IntSize? by mutableStateOf(null)

    /**
     * A pair of values representing the x and y scale factors that the content needs to be scaled
     * by to make the placeholder fill the screen.
     */
    private val activeChildScaleTarget: Offset
        get() {
            val coords = viewportCoordinates?.takeIf { it.isAttached } ?: return Offset(1f, 1f)
            val childSize = childPlaceholderSize ?: return Offset(1f, 1f)
            return Offset(
                x = coords.size.width / childSize.width.toFloat(),
                y = coords.size.height / childSize.height.toFloat()
            )
        }

    val contentZoomModifier
        get() = if (activeChild != null && !isFullyZoomedIn) {
            Modifier
                // The blur should not scale with the rest of the content, so it needs to be put
                // in a separate layer.
                .graphicsLayer {
                    // Radius of 0f causes a crash.
                    val blurRadius = 0.000001f + (zoomFactor * 40.dp.toPx())
                    renderEffect = BlurEffect(blurRadius, blurRadius)
                    // Fade the content out so that if it's drawing its own background it won't
                    // blink in and out when the content enters/leaves the composition.
                    alpha = 1f - zoomFactor
                    clip = true
                }
                .graphicsLayer {
                    // If there's an active child but the content's not being scaled it means
                    // we're on the first frame of a zoom-out and the scale couldn't be
                    // calculated yet, so the content is in the wrong place, and we shouldn't
                    // draw it.
                    alpha = 0f

                    // The scale needs to happen around the center of the placeholder.
                    val coords =
                        scaledContentCoordinates?.takeIf { it.isAttached } ?: return@graphicsLayer
                    val childBounds = placeholderBounds ?: return@graphicsLayer
                    // Ok, we have enough information to calculate the scale, so we can draw.
                    alpha = 1f
                    isContentBeingScaled = true

                    val scaleTarget = activeChildScaleTarget
                    scaleX = lerp(1f, scaleTarget.x, zoomFactor)
                    scaleY = lerp(1f, scaleTarget.y, zoomFactor)

                    val pivot = TransformOrigin(
                        pivotFractionX = childBounds.center.x / (coords.size.width),
                        pivotFractionY = childBounds.center.y / (coords.size.height)
                    )
                    transformOrigin = pivot

                    // And we need to translate so that the left edge of the placeholder will be
                    // eventually moved to the left edge of the viewport.
                    val parentCenter = viewportCoordinates!!.size.center.toOffset()
                    val distanceToCenter = parentCenter - childBounds.center
                    translationX = zoomFactor * distanceToCenter.x
                    translationY = zoomFactor * distanceToCenter.y
                }
                .composed {
                    // When the scale modifier stops being applied, it's not longer scaling.
                    DisposableEffect(this@FractalNavStateImpl) {
                        onDispose {
                            isContentBeingScaled = false
                        }
                    }
                    Modifier
                }
        } else Modifier

    /**
     * Modifier that is applied to the active child when it's currently being zoomed and has been
     * removed from the content composition to be composed by the host instead.
     */
    val childZoomModifier = Modifier
        .layout { measurable, constraints ->
            // But scale the layout bounds up instead, so the child will grow to fill the space
            // previously filled by the content.
            val childSize = childPlaceholderSize!!.toSize()
            val parentSize = viewportCoordinates!!.size.toSize()
            val scaledSize = lerp(childSize, parentSize, zoomFactor)
            val scaledConstraints = Constraints(
                minWidth = scaledSize.width.roundToInt(),
                minHeight = scaledSize.height.roundToInt(),
                maxWidth = scaledSize.width.roundToInt(),
                maxHeight = scaledSize.height.roundToInt()
            )
            val placeable = measurable.measure(scaledConstraints)
            layout(
                constraints.constrainWidth(placeable.width),
                constraints.constrainHeight(placeable.height)
            ) {
                val coords = viewportCoordinates!!
                val childCoords = activeChild?.placeholderCoordinates?.takeIf { it.isAttached }
                // If the activeChild is null, that means the animation finished _just_ before this
                // placement pass – e.g. the user could have been scrolling the content while the
                // animation was still running.
                if (childCoords == null || !isContentBeingScaled) {
                    placeable.place(IntOffset.Zero)
                } else {
                    val placeholderBounds =
                        coords.localBoundingBoxOf(childCoords, clipBounds = false)
                    placeable.place(placeholderBounds.topLeft.round())
                }
            }
        }

    @Composable
    override fun FractalNavChild(
        key: String,
        modifier: Modifier,
        content: @Composable FractalNavChildScope.() -> Unit
    ) {
        key(this, key) {
            check(!isFullyZoomedIn) {
                "FractalNavHost content shouldn't be composed when fully zoomed in."
            }

            val child = remember {
                if (activeChild?.key == key) {
                    // If there's an active child on the first composition, that means that we're
                    // starting a zoom-out and should move the child into the content composition
                    // here by re-using the child's state.
                    activeChild!!
                } else {
                    FractalChild(key, parent = this)
                }
            }

            // Register the child with its key so that zoomToChild can find it.
            DisposableEffect(child) {
                check(key !in children) {
                    "FractalNavChild with key \"$key\" has already been composed."
                }
                children[key] = child
                onDispose {
                    child.placeholderCoordinates = null
                    children -= key
                }
            }

            child.setContent(content)

            val childModifier = modifier
                // TODO why isn't onPlaced working?
                .onPlaced { child.placeholderCoordinates = it }
                .onGloballyPositioned {
                    child.placeholderCoordinates = it
                }

            // The active child will be composed by the host, so don't compose it here.
            if (child === activeChild) {
                // …but put a placeholder instead to reserve the space.
                childPlaceholderSize?.run {
                    Box(childModifier.size(
                        with(LocalDensity.current) {
                            DpSize(width.toDp(), height.toDp())
                        }
                    ))
                }
            } else {
                child.MovableContent(childModifier)
            }
        }
    }

    override fun zoomToChild(key: String) {
        val requestedChild = children.getOrElse(key) {
            throw IllegalArgumentException("No child with key \"$key\".")
        }

        // Can't zoom into a child while a different child is currently active.
        // However, if we're currently zooming out of a child, and that same child requested to
        // be zoomed in again, we can cancel the zoom out and start zooming in immediately.
        if (activeChild != null &&
            (activeChild !== requestedChild || zoomDirection != ZoomDirection.ZoomingOut)
        ) {
            return
        }
        activeChild = requestedChild

        // Capture the size before starting the animation since the child's layout node will be
        // removed from wherever it is but we need the placeholder to preserve that space.
        // This could probably be removed once speculative layout exists.
        childPlaceholderSize = activeChild?.placeholderCoordinates?.takeIf { it.isAttached }?.size
        zoomDirection = ZoomDirection.ZoomingIn

        coroutineScope.launch {
            try {
                zoomFactorAnimatable.animateTo(1f, zoomAnimationSpecFactory())
            } finally {
                zoomDirection = null
                // Do this last since it can suspend and we want to make sure the other states are
                // updated asap.
                zoomFactorAnimatable.snapTo(1f)
            }
        }
    }

    override fun zoomOut() {
        check(activeChild != null) { "Already zoomed out." }
        zoomDirection = ZoomDirection.ZoomingOut
        coroutineScope.launch {
            try {
                zoomFactorAnimatable.animateTo(0f, zoomAnimationSpecFactory())
            } finally {
                activeChild = null
                zoomDirection = null
                // Do this last since it can suspend and we want to make sure the other states are
                // updated asap.
                zoomFactorAnimatable.snapTo(0f)
            }
        }
    }
}