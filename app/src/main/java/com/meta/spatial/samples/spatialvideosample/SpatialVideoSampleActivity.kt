/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.spectrum.spectrumsports

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.isdk.IsdkPanelGrabHandle
import com.meta.spatial.isdk.updateIsdkComponentProperties
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneAudioAsset
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.runtime.TriangleMesh
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.IntentPanelRegistration
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.meta.spatial.toolkit.Equirect180ShapeOptions;
import android.widget.ImageButton
import com.meta.spatial.core.Color4
import kotlin.system.exitProcess

fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

// default activity
class SpatialVideoSampleActivity : AppSystemActivity() {

    lateinit var player: ExoPlayer
    lateinit var controllerView: View
    lateinit var controlsFadeOutTimer: CountDownTimer
    lateinit var audio: SceneAudioAsset
    var seekBar: CompletableFuture<SeekBar> = CompletableFuture<SeekBar>()
    var timestampText: CompletableFuture<TextView> = CompletableFuture<TextView>()
    var playPauseButton: CompletableFuture<ImageButton> = CompletableFuture<ImageButton>()
    val panner: ChannelMixingAudioProcessor = ChannelMixingAudioProcessor()
    var isPlaying: Boolean = false
    var isSeeking: Boolean = false
    var isFirstReadyDone: Boolean = false
    lateinit var locomotionSystem: LocomotionSystem
    lateinit var avatarSystem: AvatarSystem
    var setUri: Uri? = null
    var targetLights: Float = 1.0f
    val PERMISSIONS_REQUEST_CODE = 100
    var alphaAnimator: ObjectAnimator? = null
    var mrPanelPose: Pose = Pose()
    var skydome: Entity? = null
    var skydomeMat: SceneMaterial? = null
    var environmentGLXF: Entity? = null
    var inMrMode: Boolean = false
        private set

    private var gltfxEntity: Entity? = null
    private val activityScope = CoroutineScope(Dispatchers.Main)

    private var isShuttingDown: Boolean = false
    private var playerReleased: Boolean = false

    override fun registerFeatures(): List<SpatialFeature> {
        val features = mutableListOf<SpatialFeature>(VRFeature(this))
        if (BuildConfig.DEBUG) {
            features.add(CastInputForwardFeature(this))
            features.add(HotReloadFeature(this))
            features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
            features.add(DataModelInspectorFeature(spatial, this.componentManager))
        }
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        appPackageName = getPackageName()
        appContext = spatialContext

        panner.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))

        val audioProcessors: Array<BaseAudioProcessor> = arrayOf(panner)

        val audioSink = DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build()

        player = ExoPlayer.Builder(this, CustomRenderersFactory(this, audioSink)).build()

        audio = SceneAudioAsset.loadLocalFile("data/common/audio/ui_press_direct.ogg")

        // since this is MR mode, we want to disable the controllers rendering
        // We will enable the controllers when not in MR, but will not enable avatar
        avatarSystem = systemManager.findSystem<AvatarSystem>()
        avatarSystem.setShowControllers(false)
        avatarSystem.setShowHands(false)

        // locomotion system interferes sometimes with scrolling on panels, disabling for now
        // not needed in MR mode anyways
        locomotionSystem = systemManager.findSystem<LocomotionSystem>()
        locomotionSystem.enableLocomotion(false)

        controlsFadeOutTimer =
            object : CountDownTimer(100, 100) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    animateControllerVisibility(false)
                }
            }

        loadGLXF { composition ->
            environmentGLXF = composition.getNodeByName("MediaRoom").entity
            environmentGLXF?.let {
                val environmentMesh = it.getComponent<Mesh>()
                it.setComponent(
                    environmentMesh.apply { defaultShaderOverride = SceneMaterial.UNLIT_SHADER }
                )
            }
            //setMrMode(scene.isSystemPassthroughEnabled())
            setMrMode(true)
        }
    }

    override fun registerPanels(): List<PanelRegistration> {
        return mutableListOf(
            controlsPanelRegistration(),
            selectorPanelRegistration(),
            mrPanelRegistration(),
        )
            .apply {
                if (DEBUG) {
                    add(debugPanelRegistration())
                }
            }
    }

    private fun requestPermissions() {
        val permissionsNeeded =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    "com.oculus.permission.USE_SCENE",
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            } else {
                arrayOf(
                    "com.oculus.permission.USE_SCENE",
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                )
            }

        ActivityCompat.requestPermissions(this, permissionsNeeded, PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> {
                val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // All permissions have been granted
                    Log.i(TAG, "All permissions have been granted")
                } else {
                    // One or more permissions have been denied
                    Log.i(TAG, "One or more permissions were DENIED!")
                }
            }
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()

        // set the reference space to enable recentering
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        systemManager.registerSystem(SpatialAudioSystem(panner, this))
        componentManager.registerComponent<SpatializedAudioPanel>(SpatializedAudioPanel.Companion)

        avatarSystem.setShowHands(false)
        inMrMode = true
        locomotionSystem.enableLocomotion(false)

//        true.let { isMrMode ->
//            scene.enablePassthrough(isMrMode)
//            locomotionSystem.enableLocomotion(!isMrMode)
//            avatarSystem.setShowControllers(false)
//            avatarSystem.setShowHands(true)
//            inMrMode = isMrMode
//        }

        scene.setViewOrigin(0f, 0.0f, 0.0f, 0.0f)

        skydome =
            Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://skybox"), hittable = MeshCollision.NoCollision),
                    Material().apply {
                        baseTextureAndroidResourceId = R.drawable.skydome
                        unlit = true
                    },
                    Transform(Pose(Vector3(x = 0f, y = 0f, z = 0f))),
                    Visible(false),
                )
            )

        scene.updateIBLEnvironment("chromatic.env")
    }

    private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
        gltfxEntity = Entity.create()
        return activityScope.launch {
            glXFManager.inflateGLXF(
                Uri.parse("apk:///scenes/Composition.glxf"),
                rootEntity = gltfxEntity!!,
                onLoaded = onLoaded,
            )
        }
    }

    override fun onVRReady() {
        super.onVRReady()
        if (!isFirstReadyDone) {
            val initialPose = Pose()
            Entity(R.id.spatialized_video_panel)
                .setComponents(
                    listOf(
                        Grabbable(type = GrabbableType.PIVOT_Y, minHeight = 0.75f, maxHeight = 2.5f),
                        SpatializedAudioPanel(),
                        Transform(initialPose * Pose(Vector3(0f, 1.25f, 2f), Quaternion(0f, 0f, 0f))),
                    )
                )
            Entity(R.id.video_selector_panel)
                .setComponents(
                    listOf(
                        Grabbable(),
                        Panel(R.id.video_selector_panel),
                        Transform(
                            initialPose * Pose(Vector3(-1f, 1.25f, 1.2f), Quaternion(0f, -45f, 0f))
                        ),
                    )
                )
            Entity(R.id.controls_id)
                .setComponents(
                    listOf(
                        Panel(R.id.controls_id),
                        TransformParent(Entity(R.id.spatialized_video_panel)),
                    )
                )
            Entity(R.id.mr_panel)
                .setComponents(
                    listOf(
                        Panel(R.id.mr_panel),
                        Transform(Pose(Vector3(0.0f, -0.6f, -0.1f))),
                        TransformParent(Entity(R.id.video_selector_panel)),
                    )
                )
            environmentGLXF?.setComponents(listOf(Visible(false), Transform(initialPose)))
            if (DEBUG) {
                Entity(R.id.debug_panel)
                    .setComponents(
                        listOf(
                            Grabbable(),
                            Panel(R.id.debug_panel),
                            Transform(initialPose * Pose(Vector3(1f, 1.25f, 1f), Quaternion(0f, 45f, 0f))),
                        )
                    )
            }
            mrPanelPose = Entity(R.id.spatialized_video_panel).getComponent<Transform>().transform
            //setMrMode(scene.isSystemPassthroughEnabled())
            setMrMode(true)
            isFirstReadyDone = true
            createVideoPanel()
        }
    }

    // Video Panel
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createVideoPanel() {
        val videoPanelEntity = Entity(R.id.spatialized_video_panel)
        val settings =
            MediaPanelSettings(
                //shape = QuadShapeOptions(width = MR_SCREEN_WIDTH, height = MR_SCREEN_HEIGHT),
                shape = Equirect180ShapeOptions(5.0f),
                display = PixelDisplayOptions(width = 7168, height = 3584),
                rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight),
            )
        val panelSceneObject =
            PanelSceneObject(
                scene,
                videoPanelEntity,
                settings.toPanelConfigOptions().apply {
                    sceneMeshCreator = { texture: SceneTexture ->
                        val halfHeight = height / 2f
                        val halfWidth = width / 2f
                        val halfDepth = 0.1f
                        val rounding = 0.075f
                        val triMesh =
                            TriangleMesh(
                                8,
                                18,
                                intArrayOf(6, 6, 12, 6, 0, 6),
                                arrayOf(
                                    SceneMaterial(
                                        texture,
                                        AlphaMode.TRANSLUCENT,
                                        "data/shaders/spatial/reflect",
                                    )
                                        .apply {
                                            setStereoMode(stereoMode)
                                            setUnlit(true)
                                        },
                                    SceneMaterial(
                                        texture,
                                        AlphaMode.TRANSLUCENT,
                                        "data/shaders/spatial/shadow",
                                    )
                                        .apply { setUnlit(true) },
                                    SceneMaterial(
                                        texture,
                                        AlphaMode.HOLE_PUNCH,
                                        SceneMaterial.HOLE_PUNCH_SHADER,
                                    )
                                        .apply {
                                            setStereoMode(stereoMode)
                                            setUnlit(true)
                                        },
                                ),
                            )
                        triMesh.updateGeometry(
                            0,
                            floatArrayOf(
                                -halfWidth,
                                -halfHeight,
                                0f,
                                halfWidth,
                                -halfHeight,
                                0f,
                                halfWidth,
                                halfHeight,
                                0f,
                                -halfWidth,
                                halfHeight,
                                0f,
                                // shadow
                                -halfWidth,
                                -halfHeight,
                                halfDepth,
                                halfWidth,
                                -halfHeight,
                                halfDepth,
                                halfWidth,
                                -halfHeight,
                                -halfDepth,
                                -halfWidth,
                                -halfHeight,
                                -halfDepth,
                            ),
                            floatArrayOf(
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                1f,
                            ),
                            floatArrayOf(
                                // front
                                0f,
                                1f,
                                1f,
                                1f,
                                1f,
                                0f,
                                0f,
                                0f,
                                // shadow
                                halfWidth - rounding,
                                halfDepth - rounding,
                                halfWidth - rounding,
                                halfDepth - rounding,
                                halfWidth - rounding,
                                halfDepth - rounding,
                                halfWidth - rounding,
                                halfDepth - rounding,
                            ),
                            intArrayOf(
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                                Color.WHITE,
                            ),
                        )
                        triMesh.updatePrimitives(
                            0,
                            intArrayOf(0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2, 4, 6, 5, 4, 7, 6),
                        )
                        SceneMesh.fromTriangleMesh(triMesh, false)
                    }
                },
            )
                .apply {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    seekBar.thenAccept { it ->
                        player.addListener(
                            object : Player.Listener {
                                override fun onPlayerStateChanged(
                                    playWhenReady: Boolean,
                                    playbackState: Int,
                                ) {
                                    if (playbackState == Player.STATE_READY) {
                                        seekBar.thenAccept { it.max = player.duration.toInt() }
                                        updateTimestampText()
                                    }
                                }

                                override fun onPositionDiscontinuity(reason: Int) {
                                    it.progress = player.currentPosition.toInt()
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    if (isShuttingDown || playerReleased) {
                                        Log.w(TAG, "Ignoring ExoPlayer error during shutdown: $error")
                                        return
                                    }

                                    setUri?.let { uri -> setVideo(uri) }
                                    Log.e("ExoPlayer", "Player encountered an error: $error")
                                }
                            }
                        )

                        it.setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                    seekBar: SeekBar?,
                                    progress: Int,
                                    fromUser: Boolean,
                                ) {
                                    if (fromUser) {
                                        player.seekTo(progress.toLong())
                                        updateTimestampText()
                                        resetControllerFadeOutTimer()
                                    }
                                }

                                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                                    if (isPlaying) {
                                        // Pause the player while the user is dragging the SeekBar
                                        player.playWhenReady = false
                                    }
                                    resetControllerFadeOutTimer()
                                }

                                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                                    if (isPlaying) {
                                        // Resume the player when the user stops dragging the SeekBar
                                        player.playWhenReady = true
                                    }
                                }
                            }
                        )
                    }

                    addInputListener(
                        object : InputListener {
                            override fun onHoverStart(
                                receiver: SceneObject,
                                sourceOfInput: Entity,
                            ) {
                                animateControllerVisibility(true)
                            }

                            override fun onClick(
                                receiver: SceneObject,
                                hitInfo: HitInfo,
                                sourceOfInput: Entity,
                            ) {
                                togglePlay()
                            }

                            override fun onInput(
                                receiver: SceneObject,
                                hitInfo: HitInfo,
                                sourceOfInput: Entity,
                                changed: Int,
                                clicked: Int,
                                downTime: Long,
                            ): Boolean {
                                resetControllerFadeOutTimer()
                                return false
                            }
                        }
                    )

                    // Default media
                    Movie.fromRawVideo("custom2", "Custom")?.let { movie -> setVideo(movie.uri) }

                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed(
                        object : Runnable {
                            override fun run() {
                                if (isPlaying && !isSeeking) {
                                    seekBar.thenAccept {
                                        it.progress = player.currentPosition.toInt()
                                    }
                                }

                                updateTimestampText()

                                handler.postDelayed(this, 500)
                            }
                        },
                        500,
                    )
                    setScale(Vector3(VR_SCREEN_RATIO))
                }

        player.setVideoSurface(panelSceneObject.getSurface())

        MoviePanel.viewModel.findMovieByFileName("bt2020_10min_demo_100mbps.mp4")?.let { movie ->
            Log.d(TAG, "Setting default local video after surface is ready: ${movie.uri}")
            setVideo(movie.uri)
            playVideo()
        }

        systemManager
            .findSystem<SceneObjectSystem>()
            .addSceneObject(
                videoPanelEntity,
                CompletableFuture<SceneObject>().apply { complete(panelSceneObject) },
            )
        // mark the mesh as explicitly able to catch input
        videoPanelEntity.setComponent(Hittable())

        // Usually, ISDK is able to create panel dimensions from a Panel component. Since the video
        // player manually constructs the PanelSceneObject, we need to manually set the panel
        // dimensions & keep them up to date when switching MR modes.
        videoPanelEntity.setComponent(IsdkPanelDimensions())
        videoPanelEntity.setComponent(IsdkPanelGrabHandle())
        videoPanelEntity.setComponent(IsdkGrabbable())
        panelSceneObject.updateIsdkComponentProperties(videoPanelEntity)
    }

    private fun debugPanelRegistration(): PanelRegistration {
        return LayoutXMLPanelRegistration(
            R.id.debug_panel,
            layoutIdCreator = { R.layout.debug },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.8f, height = 0.25f),
                    display = DpDisplayOptions(width = 275.2f, height = 86f, dpi = 600),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                )
            },
            panelSetupWithRootView = { rootView, _, _ ->
                val scaleText = rootView.findViewById<TextView>(R.id.scale_text)
                val scaleBar = rootView.findViewById<SeekBar>(R.id.scale_bar)
                val scaleMax = scaleBar?.max ?: 1
                scaleBar?.setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                // in range [0, 1]
                                val normalized = progress.toDouble() / scaleMax
                                // in range [-1.5, 1.5]
                                val expRange = normalized * 3 - 1.5
                                val newScale = Math.pow(10.0, expRange).toFloat()
                                scaleText?.text = "Scale: %.2f".format(newScale)
                                Entity(R.id.spatialized_video_panel).setComponent(Scale(newScale))
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                    }
                )
            },
        )
    }

    // Movies Controller panel
    private fun controlsPanelRegistration(): PanelRegistration {
        return LayoutXMLPanelRegistration(
            R.id.controls_id,
            layoutIdCreator = { R.layout.controls },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.8f, height = 0.25f),
                    display = DpDisplayOptions(width = 275.2f, height = 86f, dpi = 600),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                )
            },
            panelSetupWithRootView = { rootView, _, _ ->
                val localSeekBar = rootView.findViewById<SeekBar>(R.id.seek_bar)!!
                seekBar.complete(localSeekBar)

                val timestampTextLocal = rootView.findViewById<TextView>(R.id.timestamp_text)!!
                timestampText.complete(timestampTextLocal)
                timestampTextLocal.text = "0:00 / 0:00"

                val playPauseButtonLocal = rootView.findViewById<ImageButton>(R.id.play_pause_button)!!
                playPauseButton.complete(playPauseButtonLocal)
                playPauseButtonLocal.setOnClickListener { togglePlay() }
                setupHoverAndTouchListeners(playPauseButtonLocal)

                controllerView = rootView
                setupHoverAndTouchListeners(controllerView)
                controllerView.alpha = 1.0f
            },
        )
    }

    private fun setupHoverAndTouchListeners(view: View) {
        view.setOnTouchListener { v, event ->
            val action = event.action
            when (action) {
                MotionEvent.ACTION_DOWN -> {}
                MotionEvent.ACTION_MOVE -> {
                    resetControllerFadeOutTimer()
                }
                MotionEvent.ACTION_UP -> {}
                MotionEvent.ACTION_CANCEL -> {}
            }
            false
        }
        view.setOnHoverListener { v, event ->
            val action = event.action
            when (action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    animateControllerVisibility(true)
                    resetControllerFadeOutTimer()
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    resetControllerFadeOutTimer()
                }
                MotionEvent.ACTION_HOVER_EXIT -> {}
            }
            true
        }
    }

    fun animateControllerVisibility(visible: Boolean) {
        if (!this::controllerView.isInitialized) {
            return
        }

        if (visible) {
            alphaAnimator =
                ObjectAnimator.ofFloat(controllerView, "alpha", controllerView.alpha, 1.0f).apply {
                    duration = 200
                    start()
                }
        } else {
            alphaAnimator =
                ObjectAnimator.ofFloat(controllerView, "alpha", controllerView.alpha, 0.0f).apply {
                    duration = 200
                    start()
                }
        }
    }

    fun resetControllerFadeOutTimer() {
        if (!this::controllerView.isInitialized) {
            return
        }

        if (isPlaying) {
            controlsFadeOutTimer.cancel()
            controlsFadeOutTimer.start()
            if (controllerView.alpha == 0.0f && alphaAnimator?.isRunning != true) {
                animateControllerVisibility(true)
            }
        }
    }

    fun togglePlay() {
        scene.playSound(audio, 1f)
        if (isPlaying) {
            pauseVideo()
        } else {
            playVideo()
        }
    }

    public fun setVideo(video: Uri) {
        setUri = video
        val mediaItem = MediaItem.fromUri(video)
        // Set the media item to be played.
        player.setMediaItem(mediaItem)
        // Prepare the player.
        player.prepare()
    }

    public fun playVideo() {
        player.play()
        isPlaying = true
        playPauseButton.thenAccept {
            it.setImageResource(R.drawable.pause)
        }
        dimLights()
        resetControllerFadeOutTimer()
    }

    public fun pauseVideo() {
        player.pause()
        isPlaying = false
        playPauseButton.thenAccept {
            it.setImageResource(R.drawable.play)
        }
        brightenLights()
        animateControllerVisibility(true)
        controlsFadeOutTimer.cancel()
    }

    public fun dimLights() {
        targetLights = 0.0f
    }

    public fun brightenLights() {
        targetLights = 1.0f
    }

    // Movies List Panel
    private fun selectorPanelRegistration(): PanelRegistration {
        return ActivityPanelRegistration(
            R.id.video_selector_panel,
            classIdCreator = { MoviePanel::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.6f, height = 1.1f),
                    display = DpDisplayOptions(width = 309.6f, height = 567.6f, dpi = 800),
                    input =
                        // want to disable left hand pinch so we can drag the panel around with hands
                        PanelInputOptions(
                            ButtonBits.ButtonA or ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
                        ),
                )
            },
        )
    }

    // Passthrough (MR) panel
    private fun mrPanelRegistration(): PanelRegistration {
        return IntentPanelRegistration(
            registrationId = R.id.mr_panel,
            intentCreator = {
                Intent(spatialContext, MRPanel::class.java).apply {
                    putExtra("isMrMode", scene.isSystemPassthroughEnabled().toString())
                }
            },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.6f, height = 0.2f),
                    display = DpDisplayOptions(width = 165.12f, height = 55.04f, dpi = 400),
                )
            },
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()

        // Quest system Passthrough / Loft can temporarily disturb scene + hand state.
        // Re-apply once immediately and again shortly after the app regains focus.
        reapplyMrModeAfterSystemOverlay()
    }

    private fun reapplyMrModeAfterSystemOverlay() {
        if (!this::locomotionSystem.isInitialized || !this::avatarSystem.isInitialized) {
            return
        }

        mainHandler.postDelayed({
            setMrMode(true)
        }, 300)
    }

    public fun setMrMode(isMrMode: Boolean) {
        Log.i(TAG, "isMrMode: " + isMrMode)
        val videoPanelEntity = Entity(R.id.spatialized_video_panel)
        if (!isMrMode) {
            mrPanelPose = videoPanelEntity.tryGetComponent<Transform>()?.transform ?: Pose()
            environmentGLXF?.setComponent(Visible(true))
            skydome?.setComponent(Visible(true))
        }
        val grabbable = videoPanelEntity.tryGetComponent<Grabbable>() ?: Grabbable()
        grabbable.enabled = isMrMode
        videoPanelEntity.setComponent(grabbable)

        if (isMrMode) {
            scene.setViewOrigin(0f, 0f, 0f, 0f) // reset locomotion
            environmentGLXF?.setComponent(Visible(false))
            //skydome?.setComponent(Visible(false)

            mrPanelPose = Pose(
                Vector3(0.0f, 1.4f, 1.0f),
                Quaternion(0f, 0f, 0f)
            )

            videoPanelEntity.setComponents(
                listOf(
                    Scale(1.0f),
                    Transform(mrPanelPose),
                    TransformParent(Entity.nullEntity())
                )
            )

            // Show the skydome in MR mode, but make it black
//            skydome?.setComponent(Visible(true))
//            skydome?.setComponent(
//                Material().apply {
//                    baseColor = Color4(0.0f, 0.0f, 0.0f, 1.0f)
//                    unlit = true
//                }
//            )
            Entity(R.id.video_selector_panel).setComponent(Visible(false))
            Entity(R.id.mr_panel).setComponent(Visible(false))
            Entity(R.id.controls_id)
                .setComponent(Transform(Pose(Vector3(0.0f, -0.6f, -0.15f), Quaternion(20f, 0f, 0f))))
        } else {
            videoPanelEntity.setComponents(
                listOf(
                    Scale(VR_SCREEN_RATIO),
                    Transform(Pose(Vector3(0.2f, 1.7f, 4.5f), Quaternion(0f, 0f, 0f))),
                )
            )
            Entity(R.id.controls_id)
                .setComponents(
                    listOf(Transform(Pose(Vector3(0.0f, -1.3f, -2.0f), Quaternion(20f, 0f, 0f))))
                )
        }

        val sceneObjectSystem = systemManager.findSystem<SceneObjectSystem>()
        val sysObject = sceneObjectSystem.getSceneObject(videoPanelEntity)?.getNow(null)
        val panel = sysObject as PanelSceneObject?
        panel?.updateIsdkComponentProperties(videoPanelEntity)

        scene.enablePassthrough(false)
        locomotionSystem.enableLocomotion(!isMrMode)
        avatarSystem.setShowControllers(!isMrMode)
        avatarSystem.setShowHands(true)
        inMrMode = isMrMode
    }

    private fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return "0:00"

        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return "%d:%02d".format(minutes, seconds)
    }

    private fun updateTimestampText() {
        if (!this::player.isInitialized) return

        val current = player.currentPosition.coerceAtLeast(0L)
        val duration =
            if (player.duration > 0L) {
                player.duration
            } else {
                0L
            }

        timestampText.thenAccept {
            it.text = "${formatTimestamp(current)} / ${formatTimestamp(duration)}"
        }
    }

    override fun onPause() {
        // Do Spatial cleanup BEFORE super.onPause(), while the XR scene is more likely still valid.
        if (isFinishing) {
            beginAppShutdownCleanup(includeSpatialReset = true)
        } else {
            // Headset overlay / pause: stop video surface pressure, but don't destroy the scene.
            pausePlayerForBackground()
        }

        super.onPause()
    }

    override fun onStop() {
        if (isFinishing) {
            beginAppShutdownCleanup(includeSpatialReset = false)
        }

        super.onStop()
    }

    private fun beginAppShutdownCleanup(includeSpatialReset: Boolean) {
        if (isShuttingDown) return
        isShuttingDown = true

        Log.d(TAG, "Beginning app shutdown cleanup. includeSpatialReset=$includeSpatialReset")

        if (this::controlsFadeOutTimer.isInitialized) {
            controlsFadeOutTimer.cancel()
        }

        alphaAnimator?.cancel()

        releasePlayerForShutdown()

        if (includeSpatialReset) {
            resetToQuestPassthroughModeEarly()
        }
    }

    private fun pausePlayerForBackground() {
        if (!this::player.isInitialized || playerReleased) return

        try {
            player.playWhenReady = false
            player.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "Error pausing player for background", t)
        }
    }

    private fun releasePlayerForShutdown() {
        if (playerReleased || !this::player.isInitialized) return

        try {
            player.playWhenReady = false
            player.stop()
            player.clearVideoSurface()
            player.release()
            playerReleased = true
            Log.d(TAG, "ExoPlayer released for shutdown")
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing ExoPlayer during shutdown", t)
        }
    }

    private fun resetToQuestPassthroughModeEarly() {
        try {
            Log.d(TAG, "Resetting passthrough before Spatial shutdown")

            scene.enablePassthrough(true)

            if (this::avatarSystem.isInitialized) {
                avatarSystem.setShowControllers(false)
                avatarSystem.setShowHands(true)
            }

            if (this::locomotionSystem.isInitialized) {
                locomotionSystem.enableLocomotion(false)
            }

            environmentGLXF?.setComponent(Visible(false))
            skydome?.setComponent(Visible(false))

            inMrMode = true
        } catch (t: Throwable) {
            Log.w(TAG, "Spatial reset failed during early shutdown", t)
        }
    }

    override fun onDestroy() {
        if (!isShuttingDown) {
            quitApplicationHard()
            return
        }

        super.onDestroy()
    }

    private val shutdownHandler = Handler(Looper.getMainLooper())

    private fun quitApplicationHard() {
        if (isShuttingDown) return
        isShuttingDown = true

        Log.d(TAG, "Hard quitting application")

        try {
            if (this::controlsFadeOutTimer.isInitialized) {
                controlsFadeOutTimer.cancel()
            }

            alphaAnimator?.cancel()

            if (this::player.isInitialized && !playerReleased) {
                player.playWhenReady = false
                player.stop()
                player.clearVideoSurface()
                player.release()
                playerReleased = true
            }

            try {
                scene.enablePassthrough(true)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not reset passthrough during quit", t)
            }

            try {
                if (this::avatarSystem.isInitialized) {
                    avatarSystem.setShowControllers(false)
                    avatarSystem.setShowHands(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not reset avatar state during quit", t)
            }

        } catch (t: Throwable) {
            Log.w(TAG, "Error during hard quit cleanup", t)
        }

        finishAndRemoveTask()

        shutdownHandler.postDelayed({
            Log.d(TAG, "Killing process after headset quit")
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }, 250)
    }

    companion object {
        const val TAG = "SpatialVideoSampleActivity"
        lateinit var appContext: Context
        lateinit var appPackageName: String

        const val LIGHTS_UP_SCALE: Float = 1.0f
        const val LIGHTS_DOWN_SCALE: Float = 0.25f

        const val MR_SCREEN_WIDTH: Float = 16.0f / 10.0f
        const val MR_SCREEN_HEIGHT: Float = 9.0f / 10.0f
        const val VR_SCREEN_RATIO: Float = 2.5f

        // spawns debug menu if true
        const val DEBUG: Boolean = false
    }
}

class CustomRenderersFactory : DefaultRenderersFactory {
    private val context_: Context
    private val audioSink_: AudioSink

    constructor(context: Context, audioSink: AudioSink) : super(context) {
        context_ = context
        audioSink_ = audioSink
    }

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> {
        val renderers =
            super.createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput,
            )
        var rendererList = renderers.toMutableList()
        val audioRenderer =
            MediaCodecAudioRenderer(
                context_,
                getCodecAdapterFactory(),
                MediaCodecSelector.DEFAULT,
                false,
                eventHandler,
                audioRendererEventListener,
                audioSink_,
            )
        rendererList.add(0, audioRenderer)
        return rendererList.toTypedArray()
    }
}