/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.mixedrealitysample

//import com.meta.spatial.castinputforward.CastInputForwardFeature
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.AnchorProceduralMesh
import com.meta.spatial.mruk.AnchorProceduralMeshConfig
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.MRUKSystem
import com.meta.spatial.physics.Physics
import com.meta.spatial.physics.PhysicsCollisionCallbackEventArgs
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.physics.PhysicsState
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.*

class MixedRealitySampleActivity : AppSystemActivity() {

  var glxfLoaded = false
  private val activityScope = CoroutineScope(Dispatchers.Main)
  private var gltfxEntity: Entity? = null
  private var ballShooter: BallShooter? = null
  private var gotAllAnchors = false
  private var debug = false
  private lateinit var procMeshSpawner: AnchorProceduralMesh
  var foundBody = false

  override fun registerFeatures(): List<SpatialFeature> {
    val features =
        mutableListOf<SpatialFeature>(
            PhysicsFeature(spatial), VRFeature(this), MRUKFeature(this, systemManager))
    if (BuildConfig.DEBUG) {
      //features.add(CastInputForwardFeature(this))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val mrukSystem = systemManager.findSystem<MRUKSystem>()

    // NOTE: Here a material could be set as well to visualize the walls, ceiling, etc
    //       It is also possible to spawn procedural meshes for volumes
    procMeshSpawner =
        AnchorProceduralMesh(
            mrukSystem,
            mapOf(
                MRUKLabel.FLOOR to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.WALL_FACE to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.CEILING to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.TABLE to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.OTHER to AnchorProceduralMeshConfig(null, true)))

    // Enable MR mode
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)

    loadGLXF().invokeOnCompletion {
      glxfLoaded = true
      val composition = glXFManager.getGLXFInfo(GLXF_SCENE)
      val bball = composition.getNodeByName("BasketBall").entity
      val mesh = bball.getComponent<Mesh>()


      ballShooter = BallShooter(mesh)
      systemManager.registerSystem(ballShooter!!)

      mrukSystem.addOnRoomAddedListener { room ->
        // If a room exists, it has a floor. Remove the default floor.
        val floor = composition.tryGetNodeByName("defaultFloor")
        floor!!.entity.destroy()
      }

      if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
        log("Scene permission has not been granted, requesting " + PERMISSION_USE_SCENE)
        requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_CODE_PERMISSION_USE_SCENE)
      } else {
        log("Scene permission has already been granted!")
        loadSceneFromDevice()
      }
    }
  }

  private fun loadSceneFromDevice() {
    val mrukSystem = systemManager.findSystem<MRUKSystem>()
    log("Loading scene from device...")
    mrukSystem.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult, _ ->
      if (result != MRUKLoadDeviceResult.SUCCESS) {
        log("Error loading scene from device: ${result}")
      } else {
        log("Scene loaded from device")
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    procMeshSpawner.destroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f)
    scene.updateIBLEnvironment("environment.env")
  }

  override fun onSceneTick() {
    super.onSceneTick()
    // Check for the body entity every tick until we find it
      if (!foundBody) {
        foundBody = getBodyEntity() != null
        if (foundBody) {
            getBodyEntity()?.setComponent((Physics(shape = "box", state = PhysicsState.KINEMATIC, dimensions = Vector3(1f, 1f, 2f))))
          println("DEBUG: Added collider to body")
            var bodyEntity = getBodyEntity()
            addPortalEventListener(bodyEntity)
        }
    }
  }

  fun getBodyEntity(): Entity? {
    val body: Entity? =
      Query.where { changed(AvatarBody.id) }
        .eval()
        .firstOrNull()
    return body
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CODE_PERMISSION_USE_SCENE &&
        permissions.size == 1 &&
        permissions[0] == PERMISSION_USE_SCENE) {
      val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED
      if (granted) {
        log("Use scene permission has been granted")
        loadSceneFromDevice()
      } else {
        log("Use scene permission was DENIED!")
      }
    }
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        PanelRegistration(R.layout.about) {
          config {
            themeResourceId = R.style.PanelAppThemeTransparent
            includeGlass = false
          }
          panel {
            val configButton = rootView?.findViewById<Button>(R.id.configure_button)
            configButton?.setOnClickListener({ scene.requestSceneCapture() })
            configButton?.setOnHoverListener(::onHoverButton)

            val debugButton = rootView?.findViewById<Button>(R.id.toggle_debug)
            debugButton?.setOnClickListener({
              debug = !debug
              spatial.enablePhysicsDebugLines(debug)
            })
            debugButton?.setOnHoverListener(::onHoverButton)
          }
        })
  }

  fun onHoverButton(v: View, event: MotionEvent): Boolean {
    // don't shoot balls while hovering over the buttons
    when (event.action) {
      MotionEvent.ACTION_HOVER_ENTER -> {
        ballShooter?.enabled = false
      }
      MotionEvent.ACTION_HOVER_EXIT -> {
        ballShooter?.enabled = true
      }
    }
    return true
  }

  fun addPortalEventListener(playerBody: Entity?) {
    if (playerBody !== null) {
      loadGLXF().invokeOnCompletion {
        val composition = glXFManager.getGLXFInfo(GLXF_SCENE)
        val portal = composition.getNodeByName("portal").entity
        // Detect when the user's body collides with the portal, which indicates the user has stepped into the portal.
        portal.registerEventListener<PhysicsCollisionCallbackEventArgs>(
          PhysicsCollisionCallbackEventArgs.EVENT_NAME) { Entity, eventArgs ->
          println("DEBUG: Object of ID ${Entity.id} collided with portal")
          if (Entity.id == playerBody.id) {
            println("DEBUG: Collided with the body!")
          }
          eventArgs.throttleTime = 200
        }
        println("DEBUG: Body id is ${playerBody.id} ")
      }
    }
  }

  private fun loadGLXF(): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
          Uri.parse("apk:///scenes/Composition.glxf"),
          rootEntity = gltfxEntity!!,
          keyName = GLXF_SCENE)
    }
  }

  companion object {
    const val TAG = "MixedRealitySampleActivityDebug"
    const val PERMISSION_USE_SCENE: String = "com.oculus.permission.USE_SCENE"
    const val REQUEST_CODE_PERMISSION_USE_SCENE: Int = 1
    const val GLXF_SCENE = "GLXF_SCENE"
  }
}

fun log(msg: String) {
  Log.d(MixedRealitySampleActivity.TAG, msg)
}

